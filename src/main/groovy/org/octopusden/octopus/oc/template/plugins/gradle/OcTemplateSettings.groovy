package org.octopusden.octopus.oc.template.plugins.gradle

import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider

import javax.inject.Inject

@CompileStatic
abstract class OcTemplateSettings {
    transient final OcTaskConfigurations taskConfigurations
    private final Project project

    protected HashMap<String, OcTemplateSettings> nestedSettings = [:]

    Map<String, OcServiceSettings> services = new HashMap<>()

    abstract Property<Boolean> getEnabled()

    abstract Property<String> getNamespace()
    abstract DirectoryProperty getWorkDir()

    abstract Property<Long> getPeriod()
    abstract Property<Integer> getAttempts()

    String nestedName

    @Inject
    OcTemplateSettings(Project project, String name = "", String parentName = "") {
        this.nestedName = parentName + name

        enabled.set(true)
        namespace.set("")
        workDir.set(project.buildDir.toPath().resolve("okd-logs").toFile())
        period.set(1500L)
        attempts.set(20)

        this.project = project

        this.taskConfigurations = new OcTaskConfigurations(this, project, name)
    }

    OcServiceSettings service(String name, Action<? super OcServiceSettings> configureAction = null) {
        System.out.println("Service registeration ${name}")
        OcServiceSettings serviceSettings = services.computeIfAbsent(name, { serviceName ->
            project.objects.newInstance(OcServiceSettings, serviceName)
        })

        if (configureAction != null) {
            configureAction.execute(serviceSettings)
        }

        return serviceSettings
    }


    Map<String, OcServiceSettings> getAllServices() {
        Map<String, OcServiceSettings> allServices = new HashMap<>(services)

        for (OcTemplateSettings nested : nestedSettings.values()) {
            allServices.putAll(nested.getAllServices())
        }

        return allServices
    }

    void isRequiredBy(Task task) {
        taskConfigurations.isRequiredBy(task)
    }

    void isRequiredBy(TaskProvider<? extends Task> taskProvider) {
        taskProvider.configure { taskConfigurations.isRequiredBy(it) }
    }

    protected OcTemplateSettings cloneAsNested(String name) {
        OcTemplateSettings settings = taskConfigurations.newTemplateSettings(name, nestedName)

        settings.enabled.set(this.enabled.get())
        settings.namespace.set(this.namespace.get())
        settings.workDir.set(this.workDir.get())
        settings.period.set(this.period.get())
        settings.attempts.set(this.attempts.get())

        return settings
    }
}
