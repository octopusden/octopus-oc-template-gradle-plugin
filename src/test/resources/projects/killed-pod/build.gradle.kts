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

    service("killable-pod") {
        templateFile.set(projectDir.resolve("template.yaml"))
        parameters.set(mapOf(
            "DOCKER_REGISTRY" to dockerRegistry
        ))
    }

    isRequiredBy(tasks.named("build"))
}

tasks.named("build") {
    doLast {
        // Force-delete the pod before the ocLogs finalizer runs.
        // This simulates the pod being OOM-killed mid-execution.
        val podName = "$projectPrefix-1-0-snapshot-killable-pod"
        exec {
            commandLine("oc", "delete", "pod", podName, "-n", okdProject!!, "--grace-period=0", "--force")
            isIgnoreExitValue = true
        }
        Thread.sleep(5000)
    }
}
