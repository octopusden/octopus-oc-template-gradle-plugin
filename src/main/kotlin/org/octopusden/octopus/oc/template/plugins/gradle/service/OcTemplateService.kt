package org.octopusden.octopus.oc.template.plugins.gradle.service

import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.process.ExecOperations
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

abstract class OcTemplateService @Inject constructor(
    private val execOperations: ExecOperations
) : BuildService<OcTemplateService.Parameters>, AutoCloseable {

    interface Parameters : BuildServiceParameters {
        val serviceName: Property<String>
        val namespace: Property<String>
        val webConsoleUrl: Property<String>
        val templateFile: RegularFileProperty
        val templateParameters: MapProperty<String, String>
        val workDir: DirectoryProperty
        val period: Property<Long>
        val attempts: Property<Int>
        val autoCleanup: Property<Boolean>
    }

    private val serviceName = parameters.serviceName.get()
    private val namespace = parameters.namespace.get()
    private val templateFile: File = parameters.templateFile.get().asFile
    private val period = parameters.period.get()
    private val attempts = parameters.attempts.get()

    private val processedFile: File
    private val logs: Directory

    private val deploymentPrefix: String
    private val podResources = mutableListOf<String>()
    private val routeResources = mutableListOf<String>()

    private val streamingProcesses = ConcurrentHashMap<String, Process>()
    private val streamingThreads = ConcurrentHashMap<String, Thread>()

    private val osType by lazy {
        System.getProperty("os.name")
    }

    private val logger: Logger = LoggerFactory.getLogger(OcTemplateService::class.java)

    init {
        with(parameters.workDir.get()) {
            asFile.mkdirs()
            processedFile = file("${serviceName}.yaml").asFile
            logs = dir("logs").also {
                it.asFile.mkdir()
            }
        }
        this.deploymentPrefix = parameters.templateParameters.get().getOrDefault("DEPLOYMENT_PREFIX", "")
    }

    fun process() {
        val errorOutput = ByteArrayOutputStream()
        val outputStream = processedFile.outputStream()
        val result = try {
            execOperations.exec {
                it.setCommandLine(
                    "oc", "process", "--local", "-o", "yaml",
                    "-f", templateFile.absolutePath,
                    *parameters.templateParameters.get().flatMap { parameter ->
                        val value = if (osType.lowercase().contains("win")) {
                            parameter.value.replace("\"", "\\\"")
                        } else {
                            parameter.value
                        }
                        listOf("-p", "${parameter.key}=$value")
                    }.toTypedArray()
                )
                it.standardOutput = outputStream
                it.errorOutput = errorOutput
                it.isIgnoreExitValue = true
            }
        } finally {
            outputStream.close()
        }

        if (result.exitValue != 0) {
            val errorMessage = String(errorOutput.toByteArray())
            val sanitizedParameters = sanitizeParameters(parameters.templateParameters.get())
            logger.error("oc process command failed with exit code ${result.exitValue}")
            logger.error("Error output: $errorMessage")
            logger.error("Template file: ${templateFile.absolutePath}")
            logger.error("Parameters: $sanitizedParameters")
            throw Exception("oc process failed: $errorMessage")
        }
    }

    private fun sanitizeParameters(params: Map<String, String>): Map<String, String> {
        val sensitiveKeys = setOf("password", "token", "secret", "apikey", "api_key", "credentials", "auth")
        return params.mapValues { (key, value) ->
            if (sensitiveKeys.any { key.lowercase().contains(it) }) {
                "<redacted>"
            } else {
                value
            }
        }
    }

    fun create() {
        stopLogStreaming()
        logs.asFile.listFiles()?.forEach { it.delete() }
        delete()
        execOperations.exec {
            it.setCommandLine("oc", "create", "-n", namespace, "-f", processedFile.absolutePath)
        }.assertNormalExitValue()
        updateCreatedResources()
        startLogStreaming()
    }

    fun waitReadiness() {
        var ready = false
        var counter = 0
        var consecutiveNoPodChecks = 0
        val maxConsecutiveNoPodChecks = 3
        var seenAnyPod = false  // Track if we've ever seen pods

        logger.info("Waiting for pod(s) with prefix '$deploymentPrefix-$serviceName' to be ready...")

        while (!ready && counter++ < attempts) {
            Thread.sleep(period)
            updateCreatedResources()
            startLogStreaming()

            val checkResult = checkPodAvailability(consecutiveNoPodChecks, maxConsecutiveNoPodChecks, seenAnyPod, "readiness check")
            if (checkResult.shouldExit) return
            consecutiveNoPodChecks = checkResult.consecutiveNoPodChecks
            seenAnyPod = checkResult.seenAnyPod  // Update the flag

            if (podResources.isEmpty()) continue

            ready = areAllPodsReady()
            if (ready) {
                logger.info(">> All pods are running and ready")
            } else {
                logger.info(">> Pods not fully ready yet, waiting...")
            }
        }

        handleReadinessResult(ready)
    }

    private data class PodAvailabilityCheckResult(
        val consecutiveNoPodChecks: Int,
        val shouldExit: Boolean,
        val seenAnyPod: Boolean  // Track if pods have been observed
    )

    private fun checkPodAvailability(
        currentConsecutiveChecks: Int,
        maxConsecutiveChecks: Int,
        seenAnyPod: Boolean,  // Pass current state
        contextLabel: String  // Caller-supplied label for the early-exit log line
    ): PodAvailabilityCheckResult {
        if (podResources.isEmpty()) {
            val newCount = currentConsecutiveChecks + 1
            logger.info(">> No pods found yet, retrying... (${newCount}/${maxConsecutiveChecks})")

            // Only exit early if we've NEVER seen pods AND hit the threshold
            // (If we've seen pods before, keep waiting - they might be recreating during rolling update)
            if (newCount >= maxConsecutiveChecks && !seenAnyPod) {
                logger.info("No pods found after $maxConsecutiveChecks checks - skipping $contextLabel")
                return PodAvailabilityCheckResult(newCount, shouldExit = true, seenAnyPod = false)
            }
            return PodAvailabilityCheckResult(newCount, shouldExit = false, seenAnyPod)
        } else {
            logger.info(">> Found ${podResources.size} pod(s): ${podResources.joinToString(", ")}")
            return PodAvailabilityCheckResult(0, shouldExit = false, seenAnyPod = true)  // Mark that we've seen pods
        }
    }

    private fun areAllPodsReady(): Boolean {
        return podResources.all { podName -> isPodReady(podName) }
    }

    private fun isPodReady(podName: String): Boolean {
        val status = getPodStatus(podName) ?: return false

        val phaseIsTerminalSuccess = status.phase == "Succeeded"
        val phaseIsRunning = status.phase == "Running"
        val allContainersReady = status.readyValues.isNotEmpty() && status.readyValues.all { it == "true" }
        // Treat missing startedValues as successful for backward compatibility (older K8s versions may not have .started field)
        val allContainersStarted = status.startedValues.isEmpty() || status.startedValues.all { it == "true" }

        val ready = phaseIsTerminalSuccess ||
            (phaseIsRunning && allContainersReady && allContainersStarted)

        if (!ready) {
            val startedStatus = if (status.startedValues.isEmpty()) "n/a" else status.startedValues.toString()
            logger.info(">> Pod '$podName' not ready: phase=${status.phase}, ready=${status.readyValues}, started=$startedStatus")
        }
        return ready
    }

    private data class PodStatus(
        val phase: String,
        val readyValues: List<String>,
        val startedValues: List<String>
    )

    private fun getPodStatus(podName: String): PodStatus? {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            it.commandLine(
                "oc", "get", "pod", podName, "-n", namespace,
                "-o", "jsonpath='{.status.phase}:{.status.containerStatuses[*].ready}:{.status.containerStatuses[*].started}'"
            )
            it.standardOutput = output
            it.isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            logger.info(">> Pod '$podName' not found")
            return null
        }

        val outputString = String(output.toByteArray()).trim().removeSurrounding("'")
        logger.info(">> Pod '$podName' status: $outputString")

        if (outputString.isEmpty()) {
            logger.info(">> Pod '$podName' status not available yet")
            return null
        }

        return parsePodStatus(outputString)
    }

    private fun parsePodStatus(statusString: String): PodStatus {
        val parts = statusString.split(":")
        val phase = parts[0]
        val readyValues = if (parts.size > 1) parts[1].trim().split(" ").filter { it.isNotBlank() } else emptyList()
        val startedValues = if (parts.size > 2) parts[2].trim().split(" ").filter { it.isNotBlank() } else emptyList()

        return PodStatus(phase, readyValues, startedValues)
    }

    private fun handleReadinessResult(ready: Boolean) {
        if (!ready) {
            if (podResources.isEmpty()) {
                logger.info("No pods found for this service - skipping readiness check")
                return
            }
            throw Exception("Pods readiness check attempts exceeded")
        }

        if (parameters.webConsoleUrl.isPresent) {
            logger.info("Pod(s) ready on:")
            podResources.forEach {
                logger.info("- $it: ${parameters.webConsoleUrl.get()}/k8s/ns/$namespace/pods/$it")
            }
        }
    }

    fun waitTermination() {
        var counter = 0
        var consecutiveNoPodChecks = 0
        val maxConsecutiveNoPodChecks = 3
        var seenAnyPod = false
        // Persist last-known phase per pod across iterations so an out-of-band GC
        // (TTL controller, concurrent cleanup) after Succeeded does not masquerade
        // as a timeout.
        val lastSeenPhase = mutableMapOf<String, String>()

        logger.info("Waiting for pod(s) with prefix '$deploymentPrefix-$serviceName' to terminate (phase=Succeeded)...")

        while (counter++ < attempts) {
            Thread.sleep(period)
            updateCreatedResources()
            startLogStreaming()

            val checkResult = checkPodAvailability(consecutiveNoPodChecks, maxConsecutiveNoPodChecks, seenAnyPod, "termination wait")
            if (checkResult.shouldExit) return
            consecutiveNoPodChecks = checkResult.consecutiveNoPodChecks
            seenAnyPod = checkResult.seenAnyPod

            podResources.forEach { name ->
                getPodStatus(name)?.phase?.let { lastSeenPhase[name] = it }
            }

            if (lastSeenPhase.isEmpty()) continue

            val failed = lastSeenPhase.filterValues { it == "Failed" }.keys
            if (failed.isNotEmpty()) {
                throw Exception("Pods finished with phase=Failed: $failed")
            }
            val terminated = lastSeenPhase.values.all { it == "Succeeded" }
            if (terminated) {
                logger.info(">> All pods terminated with phase=Succeeded")
                return
            }
            logger.info(">> Pods not yet terminated, waiting...")
        }
        throw Exception("Pods termination wait attempts exceeded")
    }

    fun logs() {
        stopLogStreaming()
        podResources.forEach { resource ->
            val logFile = logs.file("$resource.log").asFile
            val streamedSize = if (logFile.exists()) logFile.length() else 0L

            val tempFile = File.createTempFile(resource, ".log", logFile.parentFile)
            try {
                tempFile.outputStream().use { os ->
                    execOperations.exec {
                        it.setCommandLine("oc", "logs", "-n", namespace, resource, "--all-containers")
                        it.standardOutput = os
                        it.isIgnoreExitValue = true
                    }
                }
                when {
                    tempFile.length() > 0 ->
                        Files.move(tempFile.toPath(), logFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    streamedSize > 0 ->
                        logger.info("Keeping streaming log for '$resource' ($streamedSize bytes)")
                    else ->
                        if (!logFile.exists()) logFile.createNewFile()
                }
            } finally {
                tempFile.delete()
            }
        }
    }

    private fun startLogStreaming() {
        val pods = podResources.toList()
        val currentPodSet = pods.toSet()

        val stalePods = streamingThreads.keys.filter { it !in currentPodSet }
        stalePods.forEach { stalePod ->
            streamingProcesses.remove(stalePod)?.destroyForcibly()
            streamingThreads.remove(stalePod)?.interrupt()
        }

        pods.forEach { podName ->
            val logFile = logs.file("$podName.log").asFile
            val thread = Thread {
                streamLogForPod(podName, logFile)
            }.apply {
                isDaemon = true
                name = "log-stream-$podName"
            }
            if (streamingThreads.putIfAbsent(podName, thread) == null) {
                thread.start()
            }
        }
    }

    private fun streamLogForPod(podName: String, logFile: File) {
        try {
            val maxRetries = 10
            val retryDelay = 3000L
            for (attempt in 1..maxRetries) {
                if (Thread.currentThread().isInterrupted) return
                try {
                    val process = ProcessBuilder(
                        "oc", "logs", "-f", podName, "--all-containers", "-n", namespace
                    ).redirectErrorStream(true).start()
                    streamingProcesses[podName] = process
                    process.inputStream.use { input ->
                        // Append whenever the file already has content, regardless of which
                        // thread/attempt captured it. Prevents a restarted streamer from
                        // truncating data captured by a previous thread for the same pod.
                        val append = logFile.exists() && logFile.length() > 0
                        java.io.FileOutputStream(logFile, append).use { output ->
                            input.copyTo(output)
                        }
                    }
                    val exitCode = process.waitFor()
                    if (exitCode == 0) return
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                } catch (e: Exception) {
                    logger.debug("Log streaming attempt $attempt/$maxRetries for '$podName' failed: ${e.message}")
                }
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelay)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
            logger.debug("Log streaming for '$podName' gave up after $maxRetries attempts")
        } finally {
            streamingProcesses.remove(podName)
            streamingThreads.remove(podName)
        }
    }

    private fun stopLogStreaming() {
        streamingProcesses.values.forEach { it.destroyForcibly() }
        streamingThreads.values.forEach { thread ->
            thread.interrupt()
            try {
                thread.join(5000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        streamingProcesses.clear()
        streamingThreads.clear()
    }

    fun delete() {
        execOperations.exec {
            it.setCommandLine("oc", "delete", "--ignore-not-found", "-n", namespace, "-f", processedFile.absolutePath)
        }.assertNormalExitValue()
        clearCreatedResources()
    }

    private fun updateCreatedResources() {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            it.setCommandLine("oc", "get", "pods,route", "-n", namespace, "-o", "name")
            it.standardOutput = output
        }
        val outputString = String(output.toByteArray())

        clearCreatedResources()

        outputString.lines().forEach { line ->
            when {
                line.startsWith("pod/$deploymentPrefix-$serviceName") -> podResources.add(line.removePrefix("pod/"))
                line.startsWith("route/$deploymentPrefix-$serviceName") -> routeResources.add(line.removePrefix("route/"))
            }
        }
    }

    private fun clearCreatedResources() {
        podResources.clear()
        routeResources.clear()
    }

    override fun close() {
        stopLogStreaming()
        if (parameters.autoCleanup.getOrElse(true)) {
            delete()
        } else {
            logger.info("Skipping cleanup of created resources (autoCleanup=false)")
        }
    }

}