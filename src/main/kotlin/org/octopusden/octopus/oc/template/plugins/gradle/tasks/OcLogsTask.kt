package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class OcLogsTask @Inject constructor(
    private val execOperations: ExecOperations
): DefaultTask() {

    @get:Internal
    abstract val namespace: Property<String>

    @get:Internal
    abstract val pods: ListProperty<String>

    @get:Internal
    abstract val logs: DirectoryProperty

    init {
        group = "oc-template"
        description = "Fetch logs from pods"
    }

    @TaskAction
    fun logs() {
        pods.get().forEach { pod ->
            execOperations.exec {
                it.setCommandLine("oc", "logs", "-n", namespace, pod)
                it.standardOutput = logs.get().file("$pod.log").asFile.outputStream()
            }
        }
    }

}