package org.octopusden.octopus.oc.template.plugins.gradle

import org.gradle.api.Project
import javax.inject.Inject

abstract class OcTemplateExtension extends OcTemplateSetting {

    @Inject
    OcTemplateExtension(Project project) {
        super(project, "", "")
    }

    private OcTemplateSetting getOrCreateNested(String name) {
        templateNestedSettings.computeIfAbsent(name, { cloneAsNested(name) })
    }

    OcTemplateSetting group(String name) {
        getOrCreateNested(name)
    }

    // For dynamic nested configurations on Groovy DSL
    OcTemplateSetting propertyMissing(String name) {
        OcTemplateSetting templateSetting = templateNestedSettings.get(name)
        if (!templateSetting) throw new MissingPropertyException(name, getClass())
        return templateSetting
    }

    OcTemplateSetting methodMissing(String name, Object[] args) {
        if (name == "ext") throw new MissingMethodException(name, getClass(), args)

        if (args.length == 1 && args[0] instanceof Closure) {
            OcTemplateSetting templateSetting = getOrCreateNested(name)

            Closure closure = (Closure)args[0].clone()
            closure.setResolveStrategy(Closure.DELEGATE_FIRST)
            closure.setDelegate(templateSetting)

            if (closure.getMaximumNumberOfParameters() == 0) {
                closure.call()
            } else {
                closure.call(templateSetting)
            }
            return templateSetting
        }

        return getOrCreateNested(name)
    }

}
