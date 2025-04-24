import java.time.Duration

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin")
    id("com.jfrog.artifactory")
}

group = "org.octopusden.octopus"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(gradleApi())
}

kotlin {
    jvmToolchain(11)
}

gradlePlugin {
    plugins {
        create("ocTemplateGradlePlugin") {
            id = "org.octopusden.octopus.oc-template"
            displayName = "octopus-oc-template-gradle-plugin"
            description = "OC Template Gradle Plugin"
            implementationClass = "org.octopusden.octopus.oc.template.plugins.gradle.OcTemplateGradlePlugin"
        }
    }
}

artifactory {
    publish {
        val baseUrl = System.getenv().getOrDefault("ARTIFACTORY_URL", project.properties["artifactoryUrl"])
        if (baseUrl != null) {
            contextUrl = "$baseUrl/artifactory"
        }
        repository {
            repoKey = System.getenv().getOrDefault("ARTIFACTORY_REPOSITORY_KEY", project.properties["artifactoryRepositoryKey"]).toString()
            username = System.getenv().getOrDefault("ARTIFACTORY_DEPLOYER_USERNAME", project.properties["NEXUS_USER"]).toString()
            repoKey = System.getenv().getOrDefault("ARTIFACTORY_DEPLOYER_PASSWORD", project.properties["NEXUS_PASSWORD"]).toString()
        }
        defaults {
            publications("ALL_PUBLICATIONS")
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
            username.set(System.getenv("MAVEN_USERNAME"))
            password.set(System.getenv("MAVEN_PASSWORD"))
        }
    }
    transitionCheckOptions {
        maxRetries.set(60)
        delayBetween.set(Duration.ofSeconds(30))
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                name = project.name
                description = "Octopus module for oc template service plugin"
                url = "https://github.com/octopusden/octopus-oc-template-gradle-plugin.git"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                scm {
                    url = "https://github.com/octopusden/octopus-oc-template-gradle-plugin.git"
                    connection = "scm:git://github.com/octopusden/octopus-oc-template-gradle-plugin.git"
                }
                developers {
                    developer {
                        id = "octopus"
                        name = "octopus"
                    }
                }
            }
        }
    }
}

signing {
    val signingKey = project.findProperty("signingKey") as String?
    val signingPassword = project.findProperty("signingPassword") as String?

    isRequired = System.getenv().containsKey("ORG_GRADLE_PROJECT_signingKey") &&
            System.getenv().containsKey("ORG_GRADLE_PROJECT_signingPassword")

    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }

    sign(publishing.publications)
}

project.tasks.findByPath("publish")?.dependsOn(":artifactoryPublish")