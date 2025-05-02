package org.octopusden.octopus.oc.template.plugins.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class OcTemplateSettings @Inject constructor(
    val project: Project
) {
    val namespace: Property<String> = project.objects.property(String::class.java)
    val workDir: DirectoryProperty = project.objects.directoryProperty()
    val period: Property<Long> = project.objects.property(Long::class.java)
    val attempts: Property<Int> = project.objects.property(Int::class.java)
    val services: NamedDomainObjectContainer<OcServiceSettings> =
        project.container(OcServiceSettings::class.java) { name ->
            project.objects.newInstance(OcServiceSettings::class.java, name, this)
        }

    fun isRequiredBy(task: Task) {
        // TODO
    }
}