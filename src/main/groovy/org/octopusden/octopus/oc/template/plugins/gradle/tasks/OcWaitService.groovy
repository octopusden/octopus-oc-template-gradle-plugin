package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

@CompileStatic
abstract class OcWaitService extends DefaultTask {

    @Input
    abstract Property<String> getNamespace()

    @InputFile
    abstract RegularFileProperty getResourceFile()

    @Input
    abstract Property<Long> getPeriod()

    @Input
    abstract Property<Integer> getAttempts()

    private final ExecOperations execOperations

    @Inject
    OcWaitService(ExecOperations execOperations) {
        this.execOperations = execOperations
        group = "oc-template"
        description = "Create resources from templates"
    }

    @TaskAction
    void waitReadiness() {
        System.out.println("=== OcWaitService")
        System.out.println("namespace: ${namespace.get()}, resourceFile: ${resourceFile.get()}, period: ${period.get()}, attempts: ${attempts.get()}")
//        boolean ready = false
//        int counter = 0
//        OutputStream output
//
//        while (!ready && counter++ < attempts.get()) {
//            Thread.sleep(period.get())
//            output = new ByteArrayOutputStream()
//            execOperations.exec {
//                it.setCommandLine(
//                        "oc", "get", "-n", namespace, "-f", resourceFile.get().absolutePath,
//                        "-o", "jsonpath='{.items[*].status.containerStatuses[0].ready}'"
//                )
//                it.standardOutput = output
//            }.assertNormalExitValue()
//            ready = ! (new String(output.toByteArray()).contains("false"))
//        }
//        if (!ready) {
//            throw new Exception("Pods readiness check attempts exceeded")
//        }
    }

}
