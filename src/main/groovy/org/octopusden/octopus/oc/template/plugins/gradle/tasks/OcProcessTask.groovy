package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateService

@CompileStatic
abstract class OcProcessTask extends BaseOcTask {

    @Inject
    OcProcessTask() {
        super("Processes all registered OpenShift templates with parameters")
    }

    @Override
    void processService(OcTemplateService service) {
        service.process()
    }

}
