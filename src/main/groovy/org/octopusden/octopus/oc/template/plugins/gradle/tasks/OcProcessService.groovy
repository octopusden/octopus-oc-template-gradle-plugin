package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec

import javax.inject.Inject

@CompileStatic
abstract class OcProcessService extends DefaultTask {

    @OutputDirectory
    abstract DirectoryProperty getWorkDir()

    @OutputFile
    abstract RegularFileProperty getProcessedFile()

    @InputFile
    abstract RegularFileProperty getTemplateFile()

    @Input
    abstract MapProperty<String, String> getParameters()

    private final ExecOperations execOperations

    @Inject
    OcProcessService(ExecOperations execOperations) {
        this.execOperations = execOperations

        group = "oc-template"
        description = "Initialize and process OpenShift templates with parameters"
    }

    @TaskAction
    void process() {
        System.out.println("=== OcProcessService")
        System.out.println("workDir: ${workDir.get()}, templateFile: ${templateFile.get()}, parameters: ${parameters.get()}")

        File workDirFile = workDir.get().asFile
        if (!workDirFile.exists()) {
            println "Creating workDir: ${workDirFile.absolutePath}"
            workDirFile.mkdirs() // Create the directory and any necessary parent directories
        }

        execOperations.exec { ExecSpec it ->
            List<String> command = ["/opt/homebrew/bin/oc", "process", "--local", "-o", "yaml",
                                 "-f", templateFile.get().asFile.absolutePath]
            parameters.get().each {
                command.add("-p")
                command.add("${it.key}=${it.value}".toString())
            }
            it.setCommandLine(command)
            it.standardOutput = new FileOutputStream(processedFile.get().asFile)
        }.assertNormalExitValue()
    }
}
