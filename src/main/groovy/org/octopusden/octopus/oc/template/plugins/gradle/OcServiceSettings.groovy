package org.octopusden.octopus.oc.template.plugins.gradle

import groovy.transform.CompileStatic
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty

import javax.inject.Inject

@CompileStatic
abstract class OcServiceSettings {

    abstract RegularFileProperty getTemplateFile()
    abstract MapProperty<String, String> getParameters()
    abstract ListProperty<String> getPods()
    abstract ListProperty<String> getDependsOn()

    private String name

    @Inject
    OcServiceSettings(String name) {
        this.name = name
    }

}
