package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateService

@CompileStatic
class OcDeleteTask extends BaseOcTask {

    @Inject
    OcDeleteTask() {
        super("Clean up by deleting the created resources")
    }

    @Override
    void processService(OcTemplateService service) {
        service.delete()
    }

}
