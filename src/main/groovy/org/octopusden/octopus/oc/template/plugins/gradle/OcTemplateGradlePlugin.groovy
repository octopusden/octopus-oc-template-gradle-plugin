package org.octopusden.octopus.oc.template.plugins.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class OcTemplateGradlePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.extensions.create("ocTemplate", OcTemplateExtension, project)
    }

}
