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

    protected void startDiagnosticsBeforeAction() {
        doFirst {
            if (diagnosticsEnabled.getOrElse(true) && diagnosticsService.isPresent()) {
                try {
                    diagnosticsService.get().startCollection()
                } catch (Throwable t) {
                    logger.warn("Failed to start OKD diagnostics: ${t.message}")
                }
            }
        }
    }

    protected void stopDiagnosticsBeforeAction() {
        doFirst {
            if (diagnosticsEnabled.getOrElse(true) && diagnosticsService.isPresent()) {
                try {
                    diagnosticsService.get().stopCollection()
                } catch (Throwable t) {
                    logger.warn("Failed to stop OKD diagnostics: ${t.message}")
                }
            }
        }
    }

    @TaskAction
    final void process() {
        serviceNames.get().each { name ->
            def service = serviceRegistry.get().getByName(name).get()
            processService(service)
        }
    }

    abstract void processService(OcTemplateService service)

}
