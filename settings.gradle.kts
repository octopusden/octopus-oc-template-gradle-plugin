pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version settings.extra["kotlin.version"] as String
        id("io.github.gradle-nexus.publish-plugin") version settings.extra["nexus-plugin.version"] as String
        id("com.jfrog.artifactory") version settings.extra["com-jfrog-artifactory.version"] as String
        id("io.gitlab.arturbosch.detekt") version settings.extra["detekt.version"] as String
        id("org.jlleitschuh.gradle.ktlint") version settings.extra["ktlint-gradle.version"] as String
        id("org.octopusden.octopus-quality") version settings.extra["octopus-quality.version"] as String
    }
}

rootProject.name = "oc-template-gradle-plugin"
