package org.octopusden.octopus.oc.template.plugins.gradle

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider

@CompileStatic
abstract class OcTemplateSetting {
    
    transient final OcTaskConfiguration taskConfigurations
    private final Project project

    protected HashMap<String, OcTemplateSetting> templateNestedSettings = [:]
    Map<String, OcServiceSetting> serviceSettings = new HashMap<>()

    abstract Property<Boolean> getEnabled()
    abstract Property<String> getNamespace()
    abstract DirectoryProperty getWorkDir()

    abstract Property<String> getClusterDomain()
    abstract Property<String> getPrefix()
    abstract Property<String> getProjectVersion()

    abstract Property<Long> getPeriod()
    abstract Property<Integer> getAttempts()

    private String nestedName

    static final Long DEFAULT_WAIT_PERIOD = 15000L
    static final Integer DEFAULT_WAIT_ATTEMPTS = 20

    @Inject
    OcTemplateSetting(Project project, String name = "", String parentName = "") {
        this.nestedName = parentName + name
        this.project = project

        enabled.set(true)
        if (System.getenv("OKD_NAMESPACE") != null) {
            namespace.set(System.getenv("OKD_NAMESPACE"))
        }
        workDir.set(project.layout.buildDirectory.dir("oc-template"))

        if (System.getenv("OKD_CLUSTER_DOMAIN") != null) {
            clusterDomain.set(System.getenv("OKD_CLUSTER_DOMAIN"))
        }
        projectVersion.set(project.version.toString())

        period.set(DEFAULT_WAIT_PERIOD)
        attempts.set(DEFAULT_WAIT_ATTEMPTS)

        this.taskConfigurations = new OcTaskConfiguration(this, project, name)
    }

    void service(String name, Action<? super OcServiceSetting> configureAction = null) {
        if (!enabled.get()) return

        OcServiceSetting serviceSetting = serviceSettings.computeIfAbsent(name, { serviceName ->
            project.objects.newInstance(OcServiceSetting, serviceName)
        })

        if (configureAction != null) {
            configureAction.execute(serviceSetting)
        }
    }

    Map<String, OcServiceSetting> getAllServiceSettings() {
        Map<String, OcServiceSetting> allServices = new HashMap<>(serviceSettings)
        for (OcTemplateSetting nested : templateNestedSettings.values()) {
            allServices.putAll(nested.getAllServiceSettings())
        }
        return allServices
    }

    protected OcTemplateSetting cloneAsNested(String name) {
        OcTemplateSetting newTemplateSetting = project.objects.newInstance(OcTemplateSetting, project, name, nestedName)

        newTemplateSetting.enabled.set(this.enabled.get())
        newTemplateSetting.namespace.set(this.namespace.get())
        newTemplateSetting.clusterDomain.set(this.clusterDomain.get())
        newTemplateSetting.prefix.set(this.prefix.get())
        newTemplateSetting.projectVersion.set(this.projectVersion.get())
        newTemplateSetting.workDir.set(this.workDir.get())
        newTemplateSetting.period.set(this.period.get())
        newTemplateSetting.attempts.set(this.attempts.get())

        return newTemplateSetting
    }

    protected String getDeploymentPrefix() {
        return "${prefix.get()}-${projectVersion.get()}".toLowerCase().replaceAll(/[^-a-z0-9]/, "-")
    }

    void isRequiredBy(Task task) {
        taskConfigurations.isRequiredBy(task)
    }

    void isRequiredBy(TaskProvider<? extends Task> taskProvider) {
        taskProvider.configure { taskConfigurations.isRequiredBy(it) }
    }

    String getPod(String serviceName) {
        return "${getDeploymentPrefix()}-$serviceName"
    }

    String getOkdHost(String serviceName) {
        return "${getPod(serviceName)}-route-${namespace.get()}.${clusterDomain.get()}"
    }
}
