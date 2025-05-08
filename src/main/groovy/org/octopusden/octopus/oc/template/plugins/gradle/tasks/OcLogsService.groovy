package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

@CompileStatic
abstract class OcLogsService extends DefaultTask {

    @Internal
    abstract Property<String> getNamespace()

    @Internal
    abstract ListProperty<String> getPods()

    @Internal
    abstract DirectoryProperty getWorkDir()

    private final ExecOperations execOperations

    @Inject
    OcLogsService(ExecOperations execOperations) {
        this.execOperations = execOperations
        group = "oc-template"
        description = "Fetch logs from pods"
    }

    @TaskAction
    void logs() {
        System.out.println("=== OcLogsService")
        System.out.println("namespace: ${namespace.get()}, pods: ${pods.get()}, workDir: ${workDir.get()}")
//        Directory logs = workDir.get().dir("logs")
//        logs.asFile.mkdirs()
//
//        pods.get().forEach { pod ->
//            execOperations.exec {
//                it.setCommandLine("oc", "logs", "-n", namespace, pod)
//                it.standardOutput = logs.file("${pod}.log").asFile.newOutputStream()
//            }
//        }
    }

}
