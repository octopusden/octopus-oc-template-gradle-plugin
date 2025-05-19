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
import java.io.OutputStream
import javax.inject.Inject

abstract class OcTemplateService @Inject constructor(
    private val execOperations: ExecOperations
) : BuildService<OcTemplateService.Parameters>, AutoCloseable {

    interface Parameters : BuildServiceParameters {
        val namespace: Property<String>
        val templateFile: RegularFileProperty
        val templateParameters: MapProperty<String, String>
        val workDir: DirectoryProperty
        val period: Property<Long>
        val attempts: Property<Int>
    }

    private val namespace = parameters.namespace.get()
    private val templateFile: File = parameters.templateFile.get().asFile
    private val period = parameters.period.get()
    private val attempts = parameters.attempts.get()

    private val processedFile: File
    private val logs: Directory

    private val deploymentPrefix: String
    private val podResources = mutableListOf<String>()
    private val routeResources = mutableListOf<String>()

    private val logger: Logger = LoggerFactory.getLogger(OcTemplateService::class.java)

    init {
        with(parameters.workDir.get()) {
            asFile.mkdirs()
            processedFile = file("${templateFile.nameWithoutExtension}.yaml").asFile
            logs = dir("logs").also {
                it.asFile.mkdir()
            }
        }
        this.deploymentPrefix = parameters.templateParameters.get().getOrDefault("DEPLOYMENT_PREFIX", "")
    }

    fun process() {
        execOperations.exec {
            it.setCommandLine(
                "oc", "process", "--local", "-o", "yaml",
                "-f", templateFile.absolutePath,
                *parameters.templateParameters.get().flatMap { parameter ->
                    listOf("-p", "${parameter.key}=${parameter.value}")
                }.toTypedArray()
            )
            it.standardOutput = processedFile.outputStream()
        }.assertNormalExitValue()
    }

    fun create() {
        delete()
        execOperations.exec {
            it.setCommandLine("oc", "create", "-n", namespace, "-f", processedFile.absolutePath)
        }.assertNormalExitValue()
        updateCreatedResources()
    }

    fun waitReadiness() {
        if (podResources.isEmpty()) {
            logger.info("No pod resources found to check for readiness")
        } else {
            waitPodsReadiness()
        }
    }

    private fun waitPodsReadiness() {
        var ready = false
        var counter = 0
        var output: OutputStream

        val jsonPath = if (podResources.size == 1) {
            "jsonpath='{.status.containerStatuses[0].ready}'"
        } else {
            "jsonpath='{.items[*].status.containerStatuses[0].ready}'"
        }

        while (!ready && counter++ < attempts) {
            Thread.sleep(period)
            output = ByteArrayOutputStream()
            execOperations.exec {
                it.commandLine("oc", "get", "pod", *podResources.toTypedArray(), "-n", namespace, "-o", jsonPath)
                it.standardOutput = output
            }
            val outputString = String(output.toByteArray())
            logger.info(">> Check pods readiness status: $outputString")
            ready = !outputString.contains("false")
        }
        if (!ready) {
            throw Exception("Pods readiness check attempts exceeded")
        }

    }

    fun logs() {
        podResources.forEach { resource ->
            execOperations.exec {
                it.setCommandLine("oc", "logs", "-n", namespace, resource)
                it.standardOutput = logs.file("$resource.log").asFile.outputStream()
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
                line.startsWith("pod/$deploymentPrefix") -> podResources.add(line.removePrefix("pod/"))
                line.startsWith("route/$deploymentPrefix") -> routeResources.add(line.removePrefix("route/"))
            }
        }
    }

    private fun clearCreatedResources() {
        podResources.clear()
        routeResources.clear()
    }

    override fun close() {
        delete()
    }

}