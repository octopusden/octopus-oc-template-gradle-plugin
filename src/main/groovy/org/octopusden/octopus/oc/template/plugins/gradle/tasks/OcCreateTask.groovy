package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcDiagnosticsService
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateService

@CompileStatic
abstract class OcCreateTask extends BaseOcTask {

    @Inject
    OcCreateTask() {
        super("Creates resources from processed templates for all services")
        doFirst {
            if (diagnosticsEnabled.getOrElse(true) && diagnosticsService.isPresent()) {
                try {
                    OcDiagnosticsService svc = diagnosticsService.get()
                    svc.startCollection()
                } catch (Throwable t) {
                    logger.warn("Failed to start OKD diagnostics: ${t.message}")
                }
            }
        }
    }

    @Override
    void processService(OcTemplateService service) {
        service.create()
        service.waitReadiness()
    }

}
