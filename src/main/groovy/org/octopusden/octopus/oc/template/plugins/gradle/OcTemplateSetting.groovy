package org.octopusden.octopus.oc.template.plugins.gradle

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider
import java.util.zip.CRC32

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
    abstract Property<String> getWebConsoleUrl()
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
        workDir.set(project.layout.buildDirectory.dir("oc-template"))

        projectVersion.set(project.version.toString())

        period.set(DEFAULT_WAIT_PERIOD)
        attempts.set(DEFAULT_WAIT_ATTEMPTS)

        applyEnvVariableOverrides()

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
        newTemplateSetting.prefix.set(this.prefix.get())
        newTemplateSetting.projectVersion.set(this.projectVersion.get())
        newTemplateSetting.workDir.set(this.workDir.get())
        newTemplateSetting.period.set(this.period.get())
        newTemplateSetting.attempts.set(this.attempts.get())

        newTemplateSetting.applyEnvVariableOverrides(this)

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

    /**
     * Prioritizes values from environment variables <code>OKD_CLUSTER_DOMAIN</code> and
     * <code>OKD_PROJECT</code> over the current or parent configuration, if provided.
     * @param parent an optional parent setting to fall back to if environment variables are not set
     */
    private void applyEnvVariableOverrides(OcTemplateSetting parent = null) {
        project.afterEvaluate {
            applyEnvOverride("OKD_CLUSTER_DOMAIN", clusterDomain, parent?.clusterDomain?.get())
            applyEnvOverride("OKD_WEB_CONSOLE_URL", webConsoleUrl, parent?.webConsoleUrl?.isPresent() ? parent?.webConsoleUrl?.get(): null)
            applyEnvOverride("OKD_PROJECT", namespace, parent?.namespace?.get())
        }
    }

    private static void applyEnvOverride(String envKey, Property<String> targetProperty, String fallback = null) {
        String envValue = System.getenv(envKey)
        if (envValue != null && !envValue.isEmpty()) {
            targetProperty.set(envValue)
        } else if (fallback != null) {
            targetProperty.set(fallback)
        }
    }
}
