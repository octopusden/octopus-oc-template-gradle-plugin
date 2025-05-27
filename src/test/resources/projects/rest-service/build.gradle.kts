import java.net.HttpURLConnection
import java.net.URL

plugins {
    kotlin("jvm") version "2.0.20"
    id("org.octopusden.octopus.oc-template")
}

val yamlTemplateFile = project.findProperty("yaml-template-file") as? String ?: ""
val okdNamespace = project.findProperty("okd-namespace") as String?
val workDirectoryPath = project.findProperty("work-directory") as? String ?: ""
val projectPrefix = project.findProperty("project-prefix") as? String ?: ""
val dockerRegistry = project.findProperty("docker-registry") as? String ?: ""

val responseText = "OK"

ocTemplate {
    namespace.set(okdNamespace)
    workDir.set(layout.buildDirectory.dir(workDirectoryPath))
    prefix.set(projectPrefix)

    service("simple-rest") {
        templateFile.set(file(yamlTemplateFile))
        parameters.set(mapOf(
            "DOCKER_REGISTRY" to dockerRegistry,
            "RESPONSE_TEXT" to responseText
        ))
    }

    isRequiredBy(tasks.named("build"))
}

tasks.named("build") {
    doLast {
        val apiUrl = "https://" + ocTemplate.getOkdHost("simple-rest")
        val expectedResponse = "OK"

        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        val responseCode = connection.responseCode
        val responseText = connection.inputStream.bufferedReader().readText().trim()

        println("API Response Code: $responseCode")
        println("API Response Body: $responseText")

        if (responseCode != 200 || responseText != expectedResponse) {
            throw GradleException("API validation failed. Expected: '$expectedResponse', Got: '$responseText'")
        }
    }
}

