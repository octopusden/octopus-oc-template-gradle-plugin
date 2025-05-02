package org.octopusden.octopus.oc.template.plugins.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import javax.inject.Inject

abstract class OcServiceSettings @Inject constructor(
    val name: String,
    private val globalSettings: OcGlobalSettings
) {
    val templateFile: RegularFileProperty = globalSettings.project.objects.fileProperty()
    val parameters: MapProperty<String, String> =
        globalSettings.project.objects.mapProperty(String::class.java, String::class.java)
    val dependsOn: ListProperty<String> = globalSettings.project.objects.listProperty(String::class.java)
}