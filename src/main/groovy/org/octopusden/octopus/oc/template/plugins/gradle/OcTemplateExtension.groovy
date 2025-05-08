package org.octopusden.octopus.oc.template.plugins.gradle

import org.gradle.api.Project

import javax.inject.Inject

abstract class OcTemplateExtension extends OcTemplateSettings {

    @Inject
    OcTemplateExtension(Project project) {
        super(project, "", "")
    }

    private OcTemplateSettings getOrCreateNested(String name) {
        nestedSettings.computeIfAbsent(name, { cloneAsNested(name) })
    }

    OcTemplateSettings nested(String name) {
        getOrCreateNested(name)
    }

    def propertyMissing(String name) {
        def s = nestedSettings.get(name)
        if (s) {
            return s
        }
        throw new MissingPropertyException(name, getClass())
    }

    def methodMissing(String name, def args) {
        if (name == "ext") throw new MissingMethodException(name, getClass(), args)
        if (args.length == 1 && args[0] instanceof Closure) {
            OcTemplateSettings s = getOrCreateNested(name)
            Closure closure = (Closure)args[0].clone()
            closure.setResolveStrategy(Closure.DELEGATE_FIRST)
            closure.setDelegate(s)
            if (closure.getMaximumNumberOfParameters() == 0) {
                closure.call()
            } else {
                closure.call(s)
            }
            s
        } else {
            getOrCreateNested(name)
        }
    }

}
