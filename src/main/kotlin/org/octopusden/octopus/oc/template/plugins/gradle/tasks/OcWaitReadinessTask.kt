package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import javax.inject.Inject

abstract class OcWaitReadinessTask @Inject constructor(
    private val execOperations: ExecOperations,
    objects: ObjectFactory
): DefaultTask() {

    @get:Internal
    abstract val namespace: Property<String>

    @get:Internal
    abstract val resources: Property<File>

    @get:Internal
    val period: Property<Long> = objects.property(Long::class.java).convention(15000L)

    @get:Internal
    val attempts: Property<Int> = objects.property(Int::class.java).convention(20)

    init {
        group = "oc-template"
        description = "Wait for pods to be ready"
    }

    @TaskAction
    fun waitReadiness() {
        var ready = false
        var counter = 0
        var output: OutputStream
        while (!ready && counter++ < attempts.get()) {
            Thread.sleep(period.get())
            output = ByteArrayOutputStream()
            execOperations.exec {
                it.setCommandLine(
                    "oc", "get", "-n", namespace.get(), "-f", resources.get().absolutePath,
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

}