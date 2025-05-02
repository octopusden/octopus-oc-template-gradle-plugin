package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

abstract class OcCreateTask @Inject constructor(
    private val execOperations: ExecOperations
): DefaultTask() {

    @get:Internal
    abstract val namespace: Property<String>

    @get:Internal
    abstract val resources: Property<File>

    init {
        group = "oc-template"
        description = "Create resources from templates"
    }

    @TaskAction
    fun create() {
        execOperations.exec {
            it.setCommandLine("oc", "create", "-n", namespace.get(), "-f", resources.get())
        }.assertNormalExitValue()
    }

}