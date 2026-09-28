package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcDiagnosticsService
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateService
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateServiceRegistry

@CompileStatic
abstract class BaseOcTask extends DefaultTask {

    @Input
    final ListProperty<String> serviceNames = project.objects.listProperty(String)

    @Internal
    final Property<OcTemplateServiceRegistry> serviceRegistry = project.objects.property(OcTemplateServiceRegistry)

    @Internal
    final Property<OcDiagnosticsService> diagnosticsService = project.objects.property(OcDiagnosticsService)

    @Internal
    final Property<Boolean> diagnosticsEnabled = project.objects.property(Boolean).convention(true)

    @Inject
    BaseOcTask(String descriptionText) {
        group = "oc-template"
        description = descriptionText
    }

    protected void startDiagnostics() {
        if (diagnosticsEnabled.getOrElse(true) && diagnosticsService.isPresent()) {
            try {
                diagnosticsService.get().startCollection()
            } catch (Throwable t) {
                logger.warn("Failed to start OKD diagnostics: ${t.message}")
            }
        }
    }

    protected void stopDiagnostics() {
        if (diagnosticsEnabled.getOrElse(true) && diagnosticsService.isPresent()) {
            try {
                diagnosticsService.get().stopCollection()
            } catch (Throwable t) {
                logger.warn("Failed to stop OKD diagnostics: ${t.message}")
            }
        }
    }

    /**
     * Runs at the start of the task action, before any service is processed.
     * Not a doFirst from the constructor: Gradle attaches the @TaskAction after construction,
     * ahead of such a doFirst, so it ran after the action and never at all when the action failed.
     */
    protected void beforeServices() {}

    @TaskAction
    final void process() {
        beforeServices()
        serviceNames.get().each { name ->
            def service = serviceRegistry.get().getByName(name).get()
            processService(service)
        }
    }

    abstract void processService(OcTemplateService service)

}
