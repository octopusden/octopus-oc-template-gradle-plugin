package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateService

@CompileStatic
abstract class OcWaitTask extends BaseOcTask {

    @Inject
    OcWaitTask() {
        super("Waits for pod resources to become ready")
    }

    @Override
    void processService(OcTemplateService service) {
        service.waitReadiness()
    }

}
