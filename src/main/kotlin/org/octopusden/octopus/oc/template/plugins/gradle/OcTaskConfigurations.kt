package org.octopusden.octopus.oc.template.plugins.gradle

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.*
import java.io.File

class OcTaskConfigurations(
    private val settings: OcGlobalSettings,
    private val project: Project
) {
    fun registerTasks() {
        settings.services.all { service ->
            val serviceName = service.name
            val templateFile = service.templateFile.get().asFile
            val resources: File
            val logs: Directory

            with(settings.workDir.get()) {
                asFile.mkdirs()
                resources = file("${templateFile.nameWithoutExtension}.yaml").asFile
                logs = dir("logs").also {
                    it.asFile.mkdir()
                }
            }

            project.tasks.register("ocTemplateProcess$serviceName", OcProcessTask::class.java) {
                it.namespace.set(settings.namespace)
                it.resources.set(resources)
                it.templateFile.set(templateFile)
                it.parameters.set(service.parameters)
            }

            project.tasks.register("ocTemplateCreate$serviceName", OcCreateTask::class.java) {
                it.namespace.set(settings.namespace)
                it.resources.set(resources)
            }

            project.tasks.register("ocTemplateWaitReadiness$serviceName", OcWaitReadinessTask::class.java) {
                it.namespace.set(settings.namespace)
                it.resources.set(resources)
                it.period.set(settings.period)
                it.attempts.set(settings.attempts)
            }

            project.tasks.register("ocTemplateLogs$serviceName", OcLogsTask::class.java) {
                it.namespace.set(settings.namespace)
                it.pods.set(settings.pods)
                it.logs.set(logs)
            }

            project.tasks.register("ocTemplateDelete$serviceName", OcDeleteTask::class.java) {
                it.namespace.set(settings.namespace)
                it.resources.set(resources)
            }

        }
    }
}