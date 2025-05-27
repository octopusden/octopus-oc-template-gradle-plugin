pluginManagement {
    plugins {
        id("org.octopusden.octopus.oc-template") version settings.extra["octopus-oc-template.version"] as String
    }
}

rootProject.name = "nested-services"