package org.octopusden.octopus.oc.template.plugins.gradle.service

import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
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
        val podResources: ListProperty<String>
    }

    private val namespace = parameters.namespace.get()
    private val templateFile: File = parameters.templateFile.get().asFile
    private val period = parameters.period.get()
    private val attempts = parameters.attempts.get()
    private val podResources = parameters.podResources.get()

    private val processedFile: File
    private val logs: Directory

    private val logger: Logger = LoggerFactory.getLogger(OcTemplateService::class.java)

    init {
        with(parameters.workDir.get()) {
            asFile.mkdirs()
            processedFile = file("${templateFile.nameWithoutExtension}.yaml").asFile
            logs = dir("logs").also {
                it.asFile.mkdir()
            }
        }
    }

    fun process() {
        execOperations.exec {
            it.setCommandLine(
                "/opt/homebrew/bin/oc", "process", "--local", "-o", "yaml",
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
            it.setCommandLine("/opt/homebrew/bin/oc", "create", "-n", namespace, "-f", processedFile.absolutePath)
        }.assertNormalExitValue()
    }

    fun waitReadiness() {
        if (podResources.isNotEmpty()) {
            waitPodsForReady()
        } else {
            logger.info("No pod resources found to check for readiness. " +
                    "If you're using higher-level podResources (Deployments, Jobs, etc.), ensure they have the 'template.alpha.openshift.io/wait-for-ready' annotation for proper readiness handling.")
        }
    }

    private fun waitPodsForReady() {
        var ready = false
        var counter = 0
        var output: OutputStream
        while (!ready && counter++ < attempts) {
            Thread.sleep(period)
            output = ByteArrayOutputStream()
            execOperations.exec {
                it.setCommandLine(
                    "/opt/homebrew/bin/oc", "get", "-n", namespace, "-f", processedFile.absolutePath,
                    "-o", "jsonpath='{.items[*].status.containerStatuses[0].ready}'"
                )
                it.standardOutput = output
            }.assertNormalExitValue()
            val outputString = String(output.toByteArray())
            logger.info(">> Check pods readiness status: $outputString")
            ready = outputString.contains("true")
        }
        if (!ready) {
            throw Exception("Pods readiness check attempts exceeded")
        }
    }

    fun logs() {
        podResources.forEach { resource ->
            execOperations.exec {
                it.setCommandLine("/opt/homebrew/bin/oc", "logs", "-n", namespace, resource)
                it.standardOutput = logs.file("$resource.log").asFile.outputStream()
            }
        }
    }

    fun delete() {
        execOperations.exec {
            it.setCommandLine("/opt/homebrew/bin/oc", "delete", "--ignore-not-found", "-n", namespace, "-f", processedFile.absolutePath)
        }.assertNormalExitValue()
    }

    override fun close() {
        delete()
    }

}