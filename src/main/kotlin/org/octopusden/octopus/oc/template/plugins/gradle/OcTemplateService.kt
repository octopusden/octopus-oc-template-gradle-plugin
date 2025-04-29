package org.octopusden.octopus.oc.template.plugins.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.process.ExecOperations

abstract class OcTemplateService @Inject constructor(
    private val execOperations: ExecOperations
) : BuildService<OcTemplateService.Parameters>, AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val namespace: Property<String?>
        val templateFile: RegularFileProperty
        val templateParameters: MapProperty<String, String>
        val workDirectory: DirectoryProperty
    }

    private val namespace: String by lazy {
        parameters.namespace.orNull
            ?: System.getenv("OKD_NAMESPACE")
            ?: throw IllegalArgumentException("OKD namespace is not provided")
    }
    private val resources: File
    private val logs: Directory

    init {
        val templateFile = parameters.templateFile.get().asFile
        with(parameters.workDirectory.get()) {
            asFile.mkdirs()
            resources = file("${templateFile.nameWithoutExtension}.yaml").asFile
            logs = dir("logs").also {
                it.asFile.mkdir()
            }
        }
        execOperations.exec {
            it.setCommandLine(
                "oc", "process", "--local", "-o", "yaml",
                "-f", templateFile.absolutePath,
                *parameters.templateParameters.get().flatMap { parameter ->
                    listOf("-p", "${parameter.key}=${parameter.value}")
                }.toTypedArray()
            )
            it.standardOutput = resources.outputStream()
        }.assertNormalExitValue()
    }

    fun create() {
        delete()
        execOperations.exec {
            it.setCommandLine("oc", "create", "-n", namespace, "-f", resources.absolutePath)
        }.assertNormalExitValue()
    }

    fun waitPodsForReady(period: Long = 15000L, attempts: Int = 20) {
        var ready = false
        var counter = 0
        var output: OutputStream
        while (!ready && counter++ < attempts) {
            Thread.sleep(period)
            output = ByteArrayOutputStream()
            execOperations.exec {
                it.setCommandLine(
                    "oc", "get", "-n", namespace, "-f", resources.absolutePath,
                    "-o", "jsonpath='{.items[*].status.containerStatuses[0].ready}'"
                )
                it.standardOutput = output
            }.assertNormalExitValue()
            ready = !String(output.toByteArray()).contains("false")
        }
        if (!ready) {
            throw Exception("Pods readiness check attempts exceeded")
        }
    }

    fun logs(resource: String) {
        execOperations.exec {
            it.setCommandLine("oc", "logs", "-n", namespace, resource)
            it.standardOutput = logs.file("$resource.log").asFile.outputStream()
        }
    }

    fun delete() {
        execOperations.exec {
            it.setCommandLine("oc", "delete", "--ignore-not-found", "-n", namespace, "-f", resources.absolutePath)
        }.assertNormalExitValue()
    }

    override fun close() {
        delete()
    }
}