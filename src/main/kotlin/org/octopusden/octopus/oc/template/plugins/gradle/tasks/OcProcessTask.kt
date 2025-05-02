package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

abstract class OcProcessTask @Inject constructor(
    private val execOperations: ExecOperations
): DefaultTask() {

    @get:Internal
    abstract val namespace: Property<String>

    @get:Internal
    abstract val resources: Property<File>

    @get:Internal
    abstract val templateFile: Property<File>

    @get:Internal
    abstract val parameters: MapProperty<String, String>

    init {
        group = "oc-template"
        description = "Initialize and process OpenShift templates with parameters"
    }

    @TaskAction
    fun create() {
        execOperations.exec {
            it.setCommandLine(
                "oc", "process", "--local", "-o", "yaml",
                "-f", templateFile.get().absolutePath,
                *parameters.get().flatMap { parameter ->
                    listOf("-p", "${parameter.key}=${parameter.value}")
                }.toTypedArray()
            )
            it.standardOutput = resources.get().outputStream()
        }.assertNormalExitValue()
    }

}