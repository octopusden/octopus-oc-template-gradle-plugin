package org.octopusden.octopus.oc.template.plugins.gradle

import groovy.transform.CompileStatic
import javax.inject.Inject
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

@CompileStatic
abstract class OcServiceSetting {

    abstract RegularFileProperty getTemplateFile()
    abstract MapProperty<String, String> getParameters()
    abstract ListProperty<String> getDependsOn()
    abstract Property<Boolean> getWaitForCompletion()

    private String name

    @Inject
    OcServiceSetting(String name) {
        this.name = name
        waitForCompletion.convention(false)
    }

}
