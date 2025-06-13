package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateService

@CompileStatic
abstract class OcLogsTask extends BaseOcTask {

    @Inject
    OcLogsTask() {
        super("Fetches logs from pods for all services")
    }

    @Override
    void processService(OcTemplateService service) {
        service.logs()
    }

}
