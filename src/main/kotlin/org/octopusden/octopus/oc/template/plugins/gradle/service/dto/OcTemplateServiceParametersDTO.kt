package org.octopusden.octopus.oc.template.plugins.gradle.service.dto

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.octopusden.octopus.oc.template.plugins.gradle.service.OcTemplateService

data class OcTemplateServiceParametersDTO(
    override val serviceName: Property<String>,
    override val namespace: Property<String>,
    override val webConsoleUrl: Property<String>,
    override val templateFile: RegularFileProperty,
    override val templateParameters: MapProperty<String, String>,
    override val workDir: DirectoryProperty,
    override val period: Property<Long>,
    override val attempts: Property<Int>
): OcTemplateService.Parameters
