package org.octopusden.octopus.oc.template.plugins.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class OcTemplateGradlePlugin: Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "ocTemplate",
            OcGlobalSettings::class.java,
            project
        )

        project.afterEvaluate {
            val configurator = OcTaskConfigurations(extension, project)
            configurator.registerTasks()
        }
    }
}