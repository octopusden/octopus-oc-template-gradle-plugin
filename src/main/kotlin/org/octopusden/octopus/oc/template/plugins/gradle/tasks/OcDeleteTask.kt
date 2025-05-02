package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

abstract class OcDeleteTask @Inject constructor(
    private val execOperations: ExecOperations
): DefaultTask() {

    @get:Internal
    abstract val namespace: Property<String>

    @get:Internal
    abstract val resources: Property<File>

    init {
        group = "oc-template"
        description = "Clean up by deleting the created resources"
    }

    @TaskAction
    fun delete() {
        execOperations.exec {
            it.setCommandLine("oc", "delete", "--ignore-not-found", "-n", namespace.get(), "-f", resources.get().absolutePath)
        }.assertNormalExitValue()
    }

}