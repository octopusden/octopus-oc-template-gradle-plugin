plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template") version "1.0-SNAPSHOT"
}

val yamlTemplateFile = project.findProperty("yaml-template-file") as? String ?: ""
val workDirectory = project.findProperty("work-directory") as? String ?: ""

val okdProject = project.findProperty("okd-project") as? String ?: ""
val podName = project.findProperty("okd-pod-name") as? String ?: ""

val testService = ocTemplateService.register("testService") {
    namespace = okdProject
    templateFile = file(yamlTemplateFile)
    templateParameters.put("POD_NAME", podName)
    workDirectory.set(layout.buildDirectory.dir("okd"))
}

tasks.named("build") {
    doLast {
        testService.get().create()
        testService.get().waitPodsForReady()
        testService.get().logs(podName)
        testService.get().delete()
    }
}