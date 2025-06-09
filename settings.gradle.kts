pluginManagement {
    plugins {
        kotlin("jvm") version settings.extra["kotlin.version"] as String
        id("io.github.gradle-nexus.publish-plugin") version settings.extra["nexus-plugin.version"] as String
        id("com.jfrog.artifactory") version settings.extra["com-jfrog-artifactory.version"] as String
    }
}

rootProject.name = "oc-template-gradle-plugin"
