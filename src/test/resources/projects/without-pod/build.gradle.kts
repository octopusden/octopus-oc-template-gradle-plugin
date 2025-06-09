plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template")
}

val okdProject = project.findProperty("okd-project") as String?
val workDirectoryPath = project.findProperty("work-directory") as? String ?: ""
val projectPrefix = project.findProperty("project-prefix") as? String ?: ""

ocTemplate {
    namespace.set(okdProject)
    workDir.set(layout.buildDirectory.dir(workDirectoryPath))
    prefix.set(projectPrefix)

    service("simple-pvc") {
        templateFile.set(projectDir.resolve("template.yaml"))
    }

    isRequiredBy(tasks.named("build"))
}