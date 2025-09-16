plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template")
}

val okdProject = project.findProperty("okd-project") as String?
val okdClusterDomain = project.findProperty("okd-cluster-domain") as String?
val workDirectoryPath = project.findProperty("work-directory") as? String ?: ""
val dockerRegistry = project.findProperty("docker-registry") as? String ?: ""
val projectPrefix = project.findProperty("project-prefix") as? String ?: ""
val waitAttempts = (project.findProperty("okd-wait-attempts") as String?)?.toInt()

ocTemplate {
    namespace.set(okdProject)
    workDir.set(layout.buildDirectory.dir(workDirectoryPath))
    prefix.set(projectPrefix)

    group("databases").apply {
        service("postgres-1") {
            templateFile.set(projectDir.resolve("template.yaml"))
            parameters.set(mapOf(
                "POD_NAME" to "postgres-1",
                "DOCKER_REGISTRY" to dockerRegistry
            ))
        }
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

