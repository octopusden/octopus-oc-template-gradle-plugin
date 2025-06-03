import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Duration

plugins {
    kotlin("jvm")
    groovy
    `java-gradle-plugin`
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin")
    id("com.jfrog.artifactory")
}

group = "org.octopusden.octopus"
description = "Octopus module for OC template gradle plugin"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    testApi("com.platformlib:platformlib-process-local:${project.extra["platformlib-process.version"]}")
    testImplementation("org.assertj:assertj-core:${project.extra["assertj.version"]}")
    testImplementation(platform("org.junit:junit-bom:${project.extra["junit-jupiter.version"]}"))
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}

tasks.named<GroovyCompile>("compileGroovy") {
    dependsOn(tasks.named("compileKotlin"))
    classpath += files(tasks.named<KotlinCompile>("compileKotlin").get().destinationDirectory)
}

gradlePlugin {
    plugins {
        create("OcTemplateGradlePlugin") {
            id = "org.octopusden.octopus.oc-template"
            displayName = "octopus-oc-template-gradle-plugin"
            description = "OC Template Gradle Plugin"
            implementationClass = "org.octopusden.octopus.oc.template.plugins.gradle.OcTemplateGradlePlugin"
        }
    }
}

fun getTestParameter(envName: String, propertyName: String, defaultValue: String? = null): String {
    return System.getenv(envName)
        ?: (project.findProperty(propertyName) as String?)
        ?: defaultValue
        ?: throw IllegalStateException("Required test parameter '$envName' not found in environment variables or gradle properties (-P$propertyName)")
}

val testParameters by lazy {
    mapOf(
        "ocTemplateGradlePluginVersion" to project.version,
        "okdClusterDomain" to getTestParameter("OKD_CLUSTER_DOMAIN", "okd.cluster-domain"),
        "okdProject" to getTestParameter("OKD_PROJECT", "okd.project"),
        "dockerRegistry" to getTestParameter("DOCKER_REGISTRY", "docker.registry")
    )
}

tasks.test {
    useJUnitPlatform()
    dependsOn("publishToMavenLocal")
    doFirst {
        testParameters.forEach { systemProperty(it.key, it.value) }
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
            password = System.getenv().getOrDefault("ARTIFACTORY_DEPLOYER_PASSWORD", project.properties["NEXUS_PASSWORD"]).toString()
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
        create<MavenPublication>("maven") {
            pom {
                name.set(project.name)
                description.set(project.description)
                url = "https://github.com/octopusden/octopus-oc-template-gradle-plugin.git"
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    url.set("https://github.com/octopusden/octopus-oc-template-gradle-plugin.git")
                    connection.set("scm:git://github.com/octopusden/octopus-oc-template-gradle-plugin.git")
                }
                developers {
                    developer {
                        id.set("octopus")
                        name.set("octopus")
                    }
                }
            }
        }
    }
}

signing {
    isRequired = System.getenv().containsKey("ORG_GRADLE_PROJECT_signingKey") && System.getenv()
        .containsKey("ORG_GRADLE_PROJECT_signingPassword")
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["maven"])
}

project.tasks.findByPath("publish")?.dependsOn(":artifactoryPublish")