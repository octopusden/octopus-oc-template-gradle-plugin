plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template")
}

val yamlTemplateFile = project.findProperty("yaml-template-file") as? String ?: ""
val okdNamespace = project.findProperty("okd-namespace") as String?
val workDirectoryPath = project.findProperty("work-directory") as? String ?: ""

val podName = project.findProperty("okd-pod-name") as? String ?: ""
val dockerRegistry = project.findProperty("docker-registry") as? String ?: ""

val waitAttempts = (project.findProperty("okd-wait-attempts") as String?)?.toInt()

ocTemplate {
    namespace.set(okdNamespace)
    workDir.set(layout.buildDirectory.dir(workDirectoryPath))

    if (waitAttempts != null) {
        attempts.set(waitAttempts)
    }

    service("postgres-db") {
        templateFile.set(file(yamlTemplateFile))
        parameters.set(mapOf(
            "POD_NAME" to podName,
            "DOCKER_REGISTRY" to dockerRegistry
        ))
        pods.set(listOf(podName))
    }
    isRequiredBy(tasks.named("build"))
}

