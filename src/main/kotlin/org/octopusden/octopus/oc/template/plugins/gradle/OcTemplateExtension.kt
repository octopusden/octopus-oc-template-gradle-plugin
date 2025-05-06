package org.octopusden.octopus.oc.template.plugins.gradle

import groovy.lang.Closure
import groovy.lang.MissingPropertyException
import org.gradle.api.Project
import java.util.concurrent.ConcurrentHashMap

abstract class OcTemplateExtension(project: Project): OcTemplateSettings(project, "", ""){
    private val settings = ConcurrentHashMap<String, OcTemplateSettings>()

    private fun getOrCreateNested(name: String): OcTemplateSettings {
        return settings.computeIfAbsent(name) { cloneAsNested(name) }
    }

    fun createNested(name: String): OcTemplateSettings {
        return getOrCreateNested(name)
    }

    fun nested(name: String): OcTemplateSettings {
        return getOrCreateNested(name)
    }

    operator fun invoke(name: String, configure: (OcTemplateSettings) -> Unit): OcTemplateSettings {
        val nestedSettings = getOrCreateNested(name)
        configure(nestedSettings)
        return nestedSettings
    }

     fun methodMissing(name: String, args: Array<Any>) {
        if (args.isNotEmpty() && args[0] is Closure<*>) {
            val nestedSettings = getOrCreateNested(name)
            val closure = args[0] as Closure<*>
            closure.delegate = nestedSettings
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()
        } else {
            getOrCreateNested(name)
        }
    }

    @Throws(MissingPropertyException::class)
    operator fun get(name: String): OcTemplateSettings {
        return settings[name] ?: throw MissingPropertyException(name, javaClass)
    }
}