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
    workDir.set(layout.buildDirectory.dir(workDirectoryPath))
    prefix.set(projectPrefix)

    if (okdProject != null) {
        namespace.set(okdProject)
    }
    if (okdClusterDomain != null) {
        clusterDomain.set(okdClusterDomain)
    }
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

