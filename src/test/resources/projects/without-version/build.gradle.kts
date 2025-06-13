plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template")
}

val okdProject = project.findProperty("okd-project") as String?
val workDirectoryPath = project.findProperty("work-directory") as? String ?: ""
val dockerRegistry = project.findProperty("docker-registry") as? String ?: ""
val projectPrefix = project.findProperty("project-prefix") as? String ?: ""
val waitAttempts = (project.findProperty("okd-wait-attempts") as String?)?.toInt()

ocTemplate {
    namespace.set(okdProject)
    workDir.set(layout.buildDirectory.dir(workDirectoryPath))
    prefix.set(projectPrefix)

    if (waitAttempts != null) {
        attempts.set(waitAttempts)
    }

    service("postgres") {
        templateFile.set(projectDir.resolve("template.yaml"))
        parameters.set(mapOf(
            "DOCKER_REGISTRY" to dockerRegistry
        ))
    }

    isRequiredBy(tasks.named("build"))
}

