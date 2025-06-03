plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template")
}

val okdProject = project.findProperty("okd-project") as String?
val workDirectoryPath = project.findProperty("work-directory") as? String ?: ""
val dockerRegistry = project.findProperty("docker-registry") as? String ?: ""
val projectPrefix = project.findProperty("project-prefix") as? String ?: ""

ocTemplate {
    namespace.set(okdProject)
    workDir.set(layout.buildDirectory.dir("$workDirectoryPath/service1"))
    prefix.set(projectPrefix)

    service("postgres-1") {
        templateFile.set(projectDir.resolve("template.yaml"))
        parameters.set(mapOf(
            "POD_NAME" to "postgres-1",
            "DOCKER_REGISTRY" to dockerRegistry
        ))
        dependsOn.set(listOf("postgres-2"))
    }

    group("otherService").apply {
        workDir.set(layout.buildDirectory.dir("$workDirectoryPath/service2"))
        service("postgres-2") {
            templateFile.set(projectDir.resolve("template.yaml"))
            parameters.set(mapOf(
                "POD_NAME" to "postgres-2",
                "DOCKER_REGISTRY" to dockerRegistry
            ))
        }
    }

    isRequiredBy(tasks.named("build"))
}

