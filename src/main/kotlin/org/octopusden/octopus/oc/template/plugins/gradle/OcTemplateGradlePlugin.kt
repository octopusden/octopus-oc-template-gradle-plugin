package org.octopusden.octopus.oc.template.plugins.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class OcTemplateGradlePlugin: Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("ocTemplateService", OcTemplateServiceRegistry::class.java, project)
    }
}