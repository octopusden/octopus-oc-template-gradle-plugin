package org.octopusden.octopus.oc.template.plugins.gradle

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals

class OcTemplatePluginTest {

    companion object {
        const val DEFAULT_TEST_PROJECT_NAME = "template-project"
        const val DEFAULT_TEMPLATE_YAML_FILE = "template-test.yaml"
        const val DEFAULT_POD_NAME = "test-pod"
        const val DEFAULT_WORK_DIR = "okd"

        val DEFAULT_TASKS = arrayOf("clean", "build", "--info")
    }

    @Test
    fun testSimpleProject() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = DEFAULT_TEST_PROJECT_NAME
            templateYamlFileName = DEFAULT_TEMPLATE_YAML_FILE
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pokd-pod-name=$DEFAULT_POD_NAME",
                "-Pwork-directory=$DEFAULT_WORK_DIR"
            )
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/$DEFAULT_TEMPLATE_YAML_FILE"))
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/logs/$DEFAULT_POD_NAME.log"))
    }

    @Test
    fun testMissingParameter() {
        val (instance, _) = gradleProcessInstance {
            testProjectName = DEFAULT_TEST_PROJECT_NAME
            templateYamlFileName = DEFAULT_TEMPLATE_YAML_FILE
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pwork-directory=$DEFAULT_WORK_DIR"
            )
        }
        assertNotEquals(0, instance.exitCode)
        assertThat(instance.stdErr).anySatisfy {
            assertThat(it).contains("parameter POD_NAME is required and must be specified")
        }
    }

    @Test
    fun testInvalidOKDProjectNamespace() {
        val (instance, _) = gradleProcessInstance {
            testProjectName = DEFAULT_TEST_PROJECT_NAME
            templateYamlFileName = DEFAULT_TEMPLATE_YAML_FILE
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pokd-pod-name=$DEFAULT_POD_NAME",
                "-Pokd-project=invalid-namespace",
                "-Pwork-directory=$DEFAULT_WORK_DIR"
            )
        }
        assertNotEquals(0, instance.exitCode)
        assertThat(instance.stdErr).anySatisfy {
            assertThat(it).contains("in the namespace \"invalid-namespace\"")
        }
    }
}