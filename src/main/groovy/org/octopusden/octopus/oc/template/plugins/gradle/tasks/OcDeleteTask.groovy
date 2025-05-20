package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateService

@CompileStatic
class OcDeleteTask extends BaseOcTask {

    @Inject
    OcDeleteTask() {
        super("Deletes all created resources for cleanup")
    }

    @Override
    void processService(OcTemplateService service) {
        service.delete()
    }

}
