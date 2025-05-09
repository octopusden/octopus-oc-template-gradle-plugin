plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template")
}

val yamlTemplateFile = project.findProperty("yaml-template-file") as? String ?: ""
val okdNamespace = project.findProperty("okd-namespace") as String?
val workDirectoryPath = project.findProperty("work-directory") as? String ?: ""

ocTemplate {
    if (okdNamespace != null) {
        namespace.set(okdNamespace)
    }
    workDir.set(layout.buildDirectory.dir(workDirectoryPath))
    service("simple-pvc") {
        templateFile.set(file(yamlTemplateFile))
    }
    isRequiredBy(tasks.named("build"))
}