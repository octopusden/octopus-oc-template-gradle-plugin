package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

@CompileStatic
abstract class OcCreateService extends DefaultTask {

    @Input
    abstract Property<String> getNamespace()

    @InputFile
    abstract RegularFileProperty getResourceFile()

    private final ExecOperations execOperations

    @Inject
    OcCreateService(ExecOperations execOperations) {
        this.execOperations = execOperations
        group = "oc-template"
        description = "Create resources from processed templates"
    }

    @TaskAction
    void create() {
        System.out.println("=== OcCreateService")
        System.out.println("namespace: ${namespace.get()}, resourceFile: ${resourceFile.get()}")
//        execOperations.exec {
//            it.setCommandLine("oc", "create", "-n", namespace.get(), "-f", resourceFile.get())
//        }.assertNormalExitValue()
    }

}
