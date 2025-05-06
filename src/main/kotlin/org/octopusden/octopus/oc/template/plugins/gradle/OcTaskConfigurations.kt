package org.octopusden.octopus.oc.template.plugins.gradle

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.tasks.TaskProvider
import org.octopusden.octopus.oc.template.plugins.gradle.tasks.*
import java.io.File

class OcTaskConfigurations(
    private val settings: OcTemplateSettings,
    private val project: Project
) {
    fun newOcTemplateSettings(name: String, nestedName: String): OcTemplateSettings {
        return project.objects.newInstance(OcTemplateSettings::class.java, project, name, nestedName)
    }
}