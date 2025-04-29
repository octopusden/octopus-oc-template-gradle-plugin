plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template")
}

val yamlTemplateFile = project.findProperty("yaml-template-file") as? String ?: ""
val workDirectoryPath = project.findProperty("work-directory") as? String ?: ""

val okdNamespace = project.findProperty("okd-namespace") as String?
val podName = project.findProperty("okd-pod-name") as? String ?: ""

val testService = ocTemplateService.register("testService") {
    if (okdNamespace != null) {
        namespace = okdNamespace
    }
    templateFile = file(yamlTemplateFile)
    templateParameters.put("POD_NAME", podName)
    workDirectory.set(layout.buildDirectory.dir(workDirectoryPath))
}

tasks.named("build") {
    doLast {
        testService.get().create()
        testService.get().waitPodsForReady()
        testService.get().logs(podName)
        testService.get().delete()
    }
}