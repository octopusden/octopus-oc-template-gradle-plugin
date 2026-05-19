plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template")
}

val okdProject = project.findProperty("okd-project") as String?
val workDirectoryPath = project.findProperty("work-directory") as? String ?: ""
val dockerRegistry = project.findProperty("docker-registry") as? String ?: ""
val projectPrefix = project.findProperty("project-prefix") as? String ?: ""

ocTemplate {
    workDir.set(layout.buildDirectory.dir(workDirectoryPath))
    prefix.set(projectPrefix)

    if (okdProject != null) {
        namespace.set(okdProject)
    }

    service("completion-pod") {
        templateFile.set(projectDir.resolve("template.yaml"))
        parameters.set(mapOf(
            "DOCKER_REGISTRY" to dockerRegistry
        ))
        waitForCompletion.set(true)
    }

    isRequiredBy(tasks.named("build"))
}
