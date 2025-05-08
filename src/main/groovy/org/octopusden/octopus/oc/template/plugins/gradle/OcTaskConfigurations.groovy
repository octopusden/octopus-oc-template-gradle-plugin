package org.octopusden.octopus.oc.template.plugins.gradle

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.OcCreateService
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.OcDeleteService
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.OcLogsService
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.OcProcessService
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.OcWaitService

@CompileStatic
class OcTaskConfigurations {
    final static String TASK_GROUP_NAME = "oc-template"

    final OcTemplateSettings ocTemplateSettings
    final Project project
    final String settingName

    OcTaskConfigurations(OcTemplateSettings ocTemplateSettings, Project project, String name) {
        this.ocTemplateSettings = ocTemplateSettings
        this.project = project
        this.settingName = name

        project.afterEvaluate {
            if (ocTemplateSettings.enabled.get()) {
                registerTasks()
            }
        }
    }

    private void registerTasks() {
        registerServiceTasks()
        registerGroupedTasks()
    }

    private void registerServiceTasks() {
        ocTemplateSettings.services.forEach { serviceName, serviceSetting ->
            File serviceDir = project.layout.buildDirectory.file("work-dir/$serviceName").get().asFile
            File resourceFile = project.layout.buildDirectory.file("work-dir/$serviceName/resource.yaml").get().asFile

            TaskProvider<OcProcessService> processServiceTask = project.tasks.register(getServiceTaskName(serviceName, ServiceTask.PROCESS), OcProcessService) { task ->
                task.workDir.set(project.file(serviceDir))
                task.processedFile.set(project.file(resourceFile))
                task.templateFile.set(serviceSetting.templateFile)
                task.parameters.set(serviceSetting.parameters)
            }

            TaskProvider<OcCreateService> createServiceTask = project.tasks.register(getServiceTaskName(serviceName, ServiceTask.CREATE), OcCreateService) { task ->
                task.namespace.set(ocTemplateSettings.namespace)
                task.resourceFile.set(project.file(resourceFile))

                task.dependsOn(processServiceTask)

                serviceSetting.dependsOn.get().forEach { depServiceName ->
                    task.dependsOn(getServiceTaskName(depServiceName, ServiceTask.WAIT))
                }
            }

            TaskProvider<OcWaitService> waitServiceTask = project.tasks.register(getServiceTaskName(serviceName, ServiceTask.WAIT), OcWaitService) { task ->
                task.namespace.set(ocTemplateSettings.namespace)
                task.resourceFile.set(project.file(resourceFile))
                task.period.set(ocTemplateSettings.period)
                task.attempts.set(ocTemplateSettings.attempts)

                task.dependsOn(createServiceTask)
            }

            TaskProvider<OcLogsService> logsServiceTask = project.tasks.register(getServiceTaskName(serviceName, ServiceTask.LOGS), OcLogsService) { task ->
                task.namespace.set(ocTemplateSettings.namespace)
                task.pods.set(serviceSetting.pods)
                task.workDir.set(project.file(serviceDir))

                task.dependsOn(waitServiceTask)
            }

            TaskProvider<OcDeleteService> deleteServiceTask = project.tasks.register(getServiceTaskName(serviceName, ServiceTask.DELETE), OcDeleteService) { task ->
                task.namespace.set(ocTemplateSettings.namespace)
                task.resourceFile.set(project.file(resourceFile))

                task.dependsOn(processServiceTask)
            }
        }
    }

    private void registerGroupedTasks() {
        TaskProvider<DefaultTask> allProcessServicesTask = project.tasks.register(getGroupedServiceTaskName(settingName, ServiceTask.PROCESS), DefaultTask) {task ->
            task.setGroup(TASK_GROUP_NAME)
        }
        TaskProvider<DefaultTask> allCreateServicesTask = project.tasks.register(getGroupedServiceTaskName(settingName, ServiceTask.CREATE), DefaultTask) {task ->
            task.setGroup(TASK_GROUP_NAME)
        }
        TaskProvider<DefaultTask> allWaitServicesTask = project.tasks.register(getGroupedServiceTaskName(settingName, ServiceTask.WAIT), DefaultTask) {task ->
            task.setGroup(TASK_GROUP_NAME)
        }
        TaskProvider<DefaultTask> allLogsServicesTask = project.tasks.register(getGroupedServiceTaskName(settingName, ServiceTask.LOGS), DefaultTask) {task ->
            task.setGroup(TASK_GROUP_NAME)
        }
        TaskProvider<DefaultTask> allDeleteServicesTask = project.tasks.register(getGroupedServiceTaskName(settingName, ServiceTask.DELETE), DefaultTask) {task ->
            task.setGroup(TASK_GROUP_NAME)
        }

        ocTemplateSettings.getAllServices().keySet().forEach { serviceName ->
            allProcessServicesTask.configure { it.dependsOn(getServiceTaskName(serviceName as String, ServiceTask.PROCESS)) }
            allCreateServicesTask.configure { it.dependsOn(getServiceTaskName(serviceName as String, ServiceTask.CREATE)) }
            allWaitServicesTask.configure { it.dependsOn(getServiceTaskName(serviceName as String, ServiceTask.WAIT)) }
            allLogsServicesTask.configure { it.dependsOn(getServiceTaskName(serviceName as String, ServiceTask.LOGS)) }
            allDeleteServicesTask.configure { it.dependsOn(getServiceTaskName(serviceName as String, ServiceTask.DELETE)) }
        }

        allCreateServicesTask.configure { it.dependsOn(allProcessServicesTask) }
        allWaitServicesTask.configure { it.dependsOn(allCreateServicesTask) }
        allLogsServicesTask.configure { it.dependsOn(allWaitServicesTask) }
        allDeleteServicesTask.configure { it.dependsOn(allWaitServicesTask) }
    }

    enum ServiceTask {
        PROCESS("Process"),
        CREATE("Create"),
        WAIT("Wait"),
        LOGS("Logs"),
        DELETE("Delete")

        final String taskNameSuffix

        ServiceTask(String taskNameSuffix) {
            this.taskNameSuffix = taskNameSuffix
        }

        String getTaskNameSuffix() {
            return taskNameSuffix
        }
    }

    private static String getServiceTaskName(String serviceName, ServiceTask taskName) {
        return "oc${taskName.getTaskNameSuffix()}${serviceName.capitalize()}"
    }

    private static String getGroupedServiceTaskName(String groupService, ServiceTask taskName) {
        return "oc${taskName.getTaskNameSuffix()}All${groupService.capitalize()}"
    }

    @PackageScope
    void isRequiredBy(Task task) {
        task.dependsOn { project.tasks.named(getGroupedServiceTaskName(settingName, ServiceTask.WAIT)) }
        task.finalizedBy { project.tasks.named(getGroupedServiceTaskName(settingName, ServiceTask.LOGS)) }
        task.finalizedBy { project.tasks.named(getGroupedServiceTaskName(settingName, ServiceTask.DELETE)) }
    }

    @PackageScope
    OcTemplateSettings newTemplateSettings(String name, String nestedName) {
        return project.objects.newInstance(OcTemplateSettings, project, name, nestedName)
    }
}
