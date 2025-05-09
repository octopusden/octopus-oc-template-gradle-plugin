plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template")
}

val yamlTemplateFile = project.findProperty("yaml-template-file") as? String ?: ""
val okdNamespace = project.findProperty("okd-namespace") as String?
val workDirectoryPath = project.findProperty("work-directory") as? String ?: ""

val dockerRegistry = project.findProperty("docker-registry") as? String ?: ""

ocTemplate {
    namespace.set(okdNamespace)
    workDir.set(layout.buildDirectory.dir("$workDirectoryPath/service1"))

    service("postgres-db") {
        templateFile.set(file(yamlTemplateFile))
        parameters.set(mapOf(
            "POD_NAME" to "postgres-db-1",
            "DOCKER_REGISTRY" to dockerRegistry
        ))
        pods.set(listOf("postgres-db-1"))
        dependsOn.set(listOf("postgres-db-2"))
    }

    group("otherService").apply {
        enabled.set(false)
        workDir.set(layout.buildDirectory.dir("$workDirectoryPath/service2"))
        service("postgres-db-2") {
            templateFile.set(file(yamlTemplateFile))
            parameters.set(mapOf(
                "POD_NAME" to "postgres-db-2",
                "DOCKER_REGISTRY" to dockerRegistry
            ))
            pods.set(listOf("postgres-db-2"))
        }
    }

    isRequiredBy(tasks.named("build"))
}

