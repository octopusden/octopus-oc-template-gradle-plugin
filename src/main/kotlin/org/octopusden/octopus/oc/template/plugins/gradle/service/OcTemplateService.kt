package org.octopusden.octopus.oc.template.plugins.gradle.service

import javax.inject.Inject
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
        val result = execOperations.exec {
            it.setCommandLine(
                "oc", "process", "--local", "-o", "yaml",
                "-f", templateFile.absolutePath,
                *parameters.templateParameters.get().flatMap { parameter ->
                    listOf("-p", "${parameter.key}=${parameter.value}")
                }.toTypedArray()
            )
            it.standardOutput = processedFile.outputStream()
            it.errorOutput = errorOutput
            it.isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            val errorMessage = String(errorOutput.toByteArray())
            logger.error("oc process command failed with exit code ${result.exitValue}")
            logger.error("Error output: $errorMessage")
            logger.error("Template file: ${templateFile.absolutePath}")
            logger.error("Parameters: ${parameters.templateParameters.get()}")
            throw Exception("oc process failed: $errorMessage")
        }
    }

    fun create() {
        delete()
        execOperations.exec {
            it.setCommandLine("oc", "create", "-n", namespace, "-f", processedFile.absolutePath)
        }.assertNormalExitValue()
        updateCreatedResources()
    }

    fun waitReadiness() {
        var ready = false
        var counter = 0

        logger.info("Waiting for pod(s) with prefix '$deploymentPrefix-$serviceName' to be ready...")

        while (!ready && counter++ < attempts) {
            Thread.sleep(period)

            // Refresh pod list on each check to handle Deployments that create pods asynchronously
            if (podResources.isEmpty()) {
                updateCreatedResources()
                if (podResources.isEmpty()) {
                    logger.info(">> No pods found yet, retrying...")
                    continue
                } else {
                    logger.info(">> Found ${podResources.size} pod(s): ${podResources.joinToString(", ")}")
                }
            }

            // Check each pod individually by name
            val allPodsReady = podResources.all { podName ->
                val output = ByteArrayOutputStream()
                val result = execOperations.exec {
                    it.commandLine("oc", "get", "pod", podName, "-n", namespace,
                        "-o", "jsonpath='{.status.phase}:{.status.containerStatuses[0].ready}:{.status.containerStatuses[0].started}'")
                    it.standardOutput = output
                    it.isIgnoreExitValue = true
                }

                if (result.exitValue == 0) {
                    val outputString = String(output.toByteArray()).trim().removeSurrounding("'")
                    logger.info(">> Pod '$podName' status: $outputString")

                    if (outputString.isNotEmpty()) {
                        val parts = outputString.split(":")
                        // Check: phase == Running, ready == true, started == true
                        parts.size >= 3 &&
                            parts[0] == "Running" &&
                            parts[1] == "true" &&
                            parts[2] == "true"
                    } else {
                        logger.info(">> Pod '$podName' status not available yet")
                        false
                    }
                } else {
                    logger.info(">> Pod '$podName' not found")
                    false
                }
            }

            if (allPodsReady) {
                ready = true
                logger.info(">> All pods are running and ready")
            } else {
                logger.info(">> Pods not fully ready yet, waiting...")
            }
        }
        if (!ready) {
            // If no pods were ever found, that's OK (e.g., PVC-only templates)
            if (podResources.isEmpty()) {
                logger.info("No pods found for this service - skipping readiness check")
                return
            }
            throw Exception("Pods readiness check attempts exceeded")
        }
        if (parameters.webConsoleUrl.isPresent) {
            logger.info("Pod(s) ready on:")
            podResources.forEach { logger.info("- $it: ${parameters.webConsoleUrl.get()}/k8s/ns/$namespace/pods/$it") }
        }
    }

    fun logs() {
        podResources.forEach { resource ->
            val result = execOperations.exec {
                it.setCommandLine("oc", "logs", "-n", namespace, resource)
                it.standardOutput = logs.file("$resource.log").asFile.outputStream()
                it.isIgnoreExitValue = true
            }
        }
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
        if (parameters.autoCleanup.getOrElse(true)) {
            delete()
        } else {
            logger.info("Skipping cleanup of created resources (autoCleanup=false)")
        }
    }

}