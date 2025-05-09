package org.octopusden.octopus.oc.template.plugins.gradle

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty

@CompileStatic
abstract class OcServiceSetting {

    abstract RegularFileProperty getTemplateFile()
    abstract MapProperty<String, String> getParameters()
    abstract ListProperty<String> getPods()
    abstract ListProperty<String> getDependsOn()

    private String name

    @Inject
    OcServiceSetting(String name) {
        this.name = name
    }

}
