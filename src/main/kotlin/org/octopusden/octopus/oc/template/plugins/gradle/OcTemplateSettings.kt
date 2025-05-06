package org.octopusden.octopus.oc.template.plugins.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class OcTemplateSettings @Inject constructor(
    val project: Project,
    name: String = "",
    parentName: String = "",
) {
    private val nestedName = parentName + name
    private val taskConfigurations = OcTaskConfigurations(this, project)

    abstract val enabled: Property<Boolean>

    abstract val namespace: Property<String>
    abstract val workDir: DirectoryProperty

    abstract val period: Property<Long>
    abstract val attempts: Property<Int>

    private fun initializedDefault() {
        this.enabled.set(true)
        this.period.set(1500L)
        this.attempts.set(20)
    }

    fun isRequiredBy(task: Task) {
        // TODO
    }

    protected fun cloneAsNested(name: String): OcTemplateSettings {
        initializedDefault()

        val settings = taskConfigurations.newOcTemplateSettings(name, nestedName)

        settings.enabled.set(this.enabled.get())
        settings.namespace.set(this.namespace.get())
        settings.workDir.set(this.workDir.get())
        settings.period.set(this.period.get())
        settings.attempts.set(this.attempts.get())

        return settings
    }
}
