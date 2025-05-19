plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template")
}

val yamlTemplateFile = project.findProperty("yaml-template-file") as? String ?: ""
val okdNamespace = project.findProperty("okd-namespace") as String?
val workDirectoryPath = project.findProperty("work-directory") as? String ?: ""
val projectPrefix = project.findProperty("project-prefix") as? String ?: ""

ocTemplate {
    workDir.set(layout.buildDirectory.dir(workDirectoryPath))
    prefix.set(projectPrefix)

    if (okdNamespace != null) {
        namespace.set(okdNamespace)
    }

    service("simple-pvc") {
        templateFile.set(file(yamlTemplateFile))
    }

    isRequiredBy(tasks.named("build"))
}