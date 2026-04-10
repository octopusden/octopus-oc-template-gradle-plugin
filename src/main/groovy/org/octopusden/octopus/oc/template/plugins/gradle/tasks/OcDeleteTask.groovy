package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcDiagnosticsService
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateService

@CompileStatic
class OcDeleteTask extends BaseOcTask {

    @Inject
    OcDeleteTask() {
        super("Deletes all created resources for cleanup")
        doFirst {
            if (diagnosticsEnabled.getOrElse(true) && diagnosticsService.isPresent()) {
                try {
                    OcDiagnosticsService svc = diagnosticsService.get()
                    svc.stopCollection()
                } catch (Throwable t) {
                    logger.warn("Failed to stop OKD diagnostics: ${t.message}")
                }
            }
        }
    }

    @Override
    void processService(OcTemplateService service) {
        service.delete()
    }

}
