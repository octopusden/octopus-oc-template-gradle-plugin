package org.octopusden.octopus.oc.template.plugins.gradle.tasks

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateService
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateServiceRegistry

@CompileStatic
abstract class BaseOcTask extends DefaultTask {

    @Input
    final ListProperty<String> serviceNames = project.objects.listProperty(String)

    @Internal
    final Property<OcTemplateServiceRegistry> serviceRegistry = project.objects.property(OcTemplateServiceRegistry)

    @Inject
    BaseOcTask(String descriptionText) {
        group = "oc-template"
        description = descriptionText
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
