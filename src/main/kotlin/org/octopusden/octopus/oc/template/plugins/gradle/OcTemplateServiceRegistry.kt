package org.octopusden.octopus.oc.template.plugins.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider

open class OcTemplateServiceRegistry(private val project: Project) {
    fun register(name: String, configAction: OcTemplateService.Parameters.() -> Unit) : Provider<OcTemplateService> {
        return project.gradle.sharedServices
            .registerIfAbsent(name, OcTemplateService::class.java) {
                it.parameters.apply(configAction)
            }
    }
}