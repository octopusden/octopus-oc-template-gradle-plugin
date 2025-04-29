package org.octopusden.octopus.oc.template.plugins.gradle

import com.platformlib.process.api.ProcessInstance
import com.platformlib.process.builder.ProcessBuilder
import com.platformlib.process.factory.ProcessBuilders
import com.platformlib.process.local.specification.LocalProcessSpec
import org.slf4j.LoggerFactory
import java.lang.IllegalArgumentException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

val LOGGER = LoggerFactory.getLogger("org.octopusden.octopus.oc.template.plugins.gradle")!!

open class TestGradleDSL {
    lateinit var testProjectName: String
    lateinit var templateYamlFileName: String
    var additionalArguments: Array<String> = arrayOf()
    var additionalEnvVariables: Map<String, String> = mapOf()
    var tasks: Array<String> = arrayOf()
}

fun gradleProcessInstance(init: TestGradleDSL.() -> Unit): Pair<ProcessInstance, Path> {
    val testGradleDSL = TestGradleDSL()
    init.invoke(testGradleDSL)

    val ocTemplateGradlePluginVersion = System.getenv().getOrDefault("OC_TEMPLATE_GRADLE_PLUGIN_VERSION", "1.0-SNAPSHOT")
    val templateYamlPath = getResourcePath("/${testGradleDSL.templateYamlFileName}", "Template YAML file")

    val projectPath = getResourcePath("/${testGradleDSL.testProjectName}", "Test project")
    if (!Files.isDirectory(projectPath)) {
        throw IllegalArgumentException("The specified project '${testGradleDSL.testProjectName}' hasn't been found at $projectPath")
    }

    return Pair(ProcessBuilders
        .newProcessBuilder<ProcessBuilder>(LocalProcessSpec.LOCAL_COMMAND)
        .envVariables(mapOf("JAVA_HOME" to System.getProperties().getProperty("java.home")) + testGradleDSL.additionalEnvVariables)
        .logger { it.logger(LOGGER) }
        .defaultExtensionMapping()
        .workDirectory(projectPath)
        .processInstance { processInstanceConfiguration -> processInstanceConfiguration.unlimited() }
        .commandAndArguments("$projectPath/gradlew", "--no-daemon")
        .build()
        .execute(
            *(listOf(
                "-Poctopus-oc-template.version=$ocTemplateGradlePluginVersion",
                "-Pyaml-template-file=$templateYamlPath"
            ) + testGradleDSL.tasks + testGradleDSL.additionalArguments).toTypedArray())
        .toCompletableFuture()
        .join(), projectPath)
}

private fun getResourcePath(path: String, description: String): Path {
    val resource = TestGradleDSL::class.java.getResource(path)
        ?: error("$description '$path' not found in resources")
    return Paths.get(resource.toURI())
}
