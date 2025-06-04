package org.octopusden.octopus.oc.template.plugins.gradle.runner

import com.platformlib.process.api.ProcessInstance
import com.platformlib.process.builder.ProcessBuilder
import com.platformlib.process.factory.ProcessBuilders
import com.platformlib.process.local.specification.LocalProcessSpec
import java.lang.IllegalArgumentException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

open class TestGradleDSL {
    lateinit var testProjectName: String
    var additionalArguments: Array<String> = arrayOf()
    var additionalEnvVariables: Map<String, String> = mapOf()
    var tasks: Array<String> = arrayOf()
}

fun gradleProcessInstance(init: TestGradleDSL.() -> Unit): Pair<ProcessInstance, Path> {
    val testGradleDSL = TestGradleDSL()
    init.invoke(testGradleDSL)

    val projectPath = getResourcePath("/${testGradleDSL.testProjectName}", "Test project")
    if (!Files.isDirectory(projectPath)) {
        throw IllegalArgumentException("The specified project '${testGradleDSL.testProjectName}' hasn't been found at $projectPath")
    }

    return Pair(ProcessBuilders
        .newProcessBuilder<ProcessBuilder>(LocalProcessSpec.LOCAL_COMMAND)
        .envVariables(mapOf(
            "OKD_PROJECT" to "",            // remove inherited OKD_PROJECT from parent process
            "OKD_CLUSTER_DOMAIN" to "",     // remove inherited OKD_CLUSTER_DOMAIN from parent process
            "JAVA_HOME" to System.getProperty("java.home")
        ) + testGradleDSL.additionalEnvVariables)
        .redirectStandardOutput(System.out)
        .redirectStandardError(System.err)
        .defaultExtensionMapping()
        .workDirectory(projectPath)
        .processInstance { processInstanceConfiguration -> processInstanceConfiguration.unlimited() }
        .commandAndArguments("$projectPath/gradlew", "--no-daemon")
        .build()
        .execute(
            *(listOf(
                "-Poctopus-oc-template.version=${System.getProperty("ocTemplateGradlePluginVersion")}"
            ) + testGradleDSL.tasks + testGradleDSL.additionalArguments).toTypedArray())
        .toCompletableFuture()
        .join(), projectPath)
}

private fun getResourcePath(path: String, description: String): Path {
    val resource = TestGradleDSL::class.java.getResource(path)
        ?: error("$description '$path' not found in resources")
    return Paths.get(resource.toURI())
}
