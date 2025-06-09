package org.octopusden.octopus.oc.template.plugins.gradle

import groovy.transform.CompileStatic
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateService
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateServiceDependencyGraph
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateServiceRegistry
import org.octopusden.octopus.oc.template.plugins.gradle.service.dto.OcTemplateServiceParametersDTO
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.OcCreateTask
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.OcDeleteTask
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.OcLogsTask
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.OcProcessTask
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.OcWaitTask

import javax.inject.Provider

@CompileStatic
class OcTaskConfiguration {

    final OcTemplateSetting ocTemplateSettings
    final Project project
    final String settingName

    private TaskProvider<OcProcessTask> ocProcessTask
    private TaskProvider<OcCreateTask> ocCreateTask
    private TaskProvider<OcWaitTask> ocWaitTask
    private TaskProvider<OcLogsTask> ocLogsTask
    private TaskProvider<OcDeleteTask> ocDeleteTask

    private final OcTemplateServiceDependencyGraph serviceDependencyGraph

    OcTaskConfiguration(OcTemplateSetting ocTemplateSettings, Project project, String name) {
        this.ocTemplateSettings = ocTemplateSettings
        this.project = project
        this.settingName = name
        this.serviceDependencyGraph = new OcTemplateServiceDependencyGraph()

        project.afterEvaluate {
            if (ocTemplateSettings.enabled.get()) {
                registerBuildServices()
                registerServiceDependencies()
                registerTasks()
            }
        }
    }

    private void registerBuildServices() {
        ocTemplateSettings.serviceSettings.forEach { serviceName, serviceSetting ->
            getServiceRegistry().register(serviceName,
                new OcTemplateServiceParametersDTO(
                    project.objects.property(String).value(serviceName),
                    ocTemplateSettings.namespace,
                    ocTemplateSettings.webConsoleUrl,
                    serviceSetting.templateFile,
                    project.objects.mapProperty(String, String).value(getDefaultParameters() + serviceSetting.parameters.get()),
                    ocTemplateSettings.workDir,
                    ocTemplateSettings.period,
                    ocTemplateSettings.attempts
                )) as Provider<OcTemplateService>
            serviceDependencyGraph.add(serviceName, serviceSetting.dependsOn.get())
        }
    }

    private void registerServiceDependencies() {
        ocTemplateSettings.getAllServiceSettings().forEach { serviceName, serviceSetting ->
            serviceDependencyGraph.add(serviceName, serviceSetting.dependsOn.get())
        }
    }

    private void registerTasks() {
        this.ocProcessTask = project.tasks.register(generateTaskName(settingName, OcTemplateTaskType.PROCESS), OcProcessTask) { task ->
            task.serviceNames.set(serviceDependencyGraph.getOrdered())
            task.serviceRegistry.set(getServiceRegistry())
        }

        this.ocCreateTask = project.tasks.register(generateTaskName(settingName, OcTemplateTaskType.CREATE), OcCreateTask) { task ->
            task.serviceNames.set(serviceDependencyGraph.getOrdered())
            task.serviceRegistry.set(getServiceRegistry())
            task.dependsOn(ocProcessTask)
        }

        this.ocWaitTask = project.tasks.register(generateTaskName(settingName, OcTemplateTaskType.WAIT), OcWaitTask) { task ->
            task.serviceNames.set(serviceDependencyGraph.getOrdered())
            task.serviceRegistry.set(getServiceRegistry())
        }

        this.ocLogsTask = project.tasks.register(generateTaskName(settingName, OcTemplateTaskType.LOGS), OcLogsTask) { task ->
            task.serviceNames.set(serviceDependencyGraph.getOrdered())
            task.serviceRegistry.set(getServiceRegistry())
        }

        this.ocDeleteTask = project.tasks.register(generateTaskName(settingName, OcTemplateTaskType.DELETE), OcDeleteTask) { task ->
            task.serviceNames.set(serviceDependencyGraph.getOrdered())
            task.serviceRegistry.set(getServiceRegistry())
        }
    }

    private static String generateTaskName(String settingName, OcTemplateTaskType taskName) {
        return "oc${taskName.getTaskNameSuffix()}${settingName.capitalize()}"
    }

    void isRequiredBy(Task task) {
        task.dependsOn { ocCreateTask }
        task.finalizedBy { ocLogsTask }
        task.finalizedBy { ocDeleteTask }
    }

    private OcTemplateServiceRegistry getServiceRegistry() {
        OcTemplateServiceRegistry extension = project.extensions.findByType(OcTemplateServiceRegistry)
        if (extension == null) {
            extension = project.extensions.create("ocTemplateServiceRegistry", OcTemplateServiceRegistry, project)
        }
        return extension
    }

    private Map<String, String> getDefaultParameters() {
        return [
            "DEPLOYMENT_PREFIX": ocTemplateSettings.getDeploymentPrefix()
        ]
    }
}
