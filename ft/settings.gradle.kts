pluginManagement {
    plugins {
        kotlin("jvm") version settings.extra["kotlin.version"] as String
    }
}

rootProject.name = "oc-template-gradle-plugin-ft"