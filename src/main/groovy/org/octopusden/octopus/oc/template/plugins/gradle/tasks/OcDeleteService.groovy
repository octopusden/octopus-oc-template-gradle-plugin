package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

@CompileStatic
abstract class OcDeleteService extends DefaultTask {

    @Internal
    abstract Property<String> getNamespace()

    @Internal
    abstract RegularFileProperty getResourceFile()

    private final ExecOperations execOperations

    @Inject
    OcDeleteService(ExecOperations execOperations) {
        this.execOperations = execOperations
        group = "oc-template"
        description = "Clean up by deleting the created resources"
    }

    @TaskAction
    void delete() {
        System.out.println("=== OcDeleteService")
        System.out.println("namespace: ${namespace.get()}, resourceFile: ${resourceFile.get()}")
//        execOperations.exec {
//            it.setCommandLine("oc", "delete", "--ignore-not-found", "-n", namespace.get(), "-f", resourceFile.get().absolutePath)
//        }.assertNormalExitValue()
    }

}
