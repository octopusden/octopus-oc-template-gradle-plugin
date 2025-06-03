package org.octopusden.octopus.oc.template.plugins.gradle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.octopusden.octopus.oc.template.plugins.gradle.runner.gradleProcessInstance

class OcTemplatePluginTest {

    companion object {
        const val WORK_DIR = "okd"
        const val DEPLOYMENT_PREFIX = "oc-template-ft"
        private val TASKS = arrayOf("clean", "build", "--info", "--stacktrace")
        private val OKD_PROJECT: String = System.getProperty("okdProject")
        private val DOCKER_REGISTRY: String = System.getProperty("dockerRegistry")
        private val DEFAULT_PARAMETERS = arrayOf(
            "-Pokd-project=$OKD_PROJECT",
            "-Pwork-directory=$WORK_DIR",
            "-Pproject-prefix=$DEPLOYMENT_PREFIX",
            "-Pdocker-registry=$DOCKER_REGISTRY"
        )
        private val DEFAULT_ENV_VARIABLES = mapOf("OKD_CLUSTER_DOMAIN" to System.getProperty("okdClusterDomain"))
    }

    @Test
    fun testSimpleProject() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/simple-project"
            tasks = TASKS
            additionalArguments = DEFAULT_PARAMETERS
            additionalEnvVariables = DEFAULT_ENV_VARIABLES
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$WORK_DIR/template.yaml")).exists()
        assertThat(projectPath.resolve("build/$WORK_DIR/logs/${getLogFileName("postgres")}")).exists()
    }

    @Test
    fun testSimpleProjectWithoutPod() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/without-pod"
            tasks = TASKS
            additionalArguments = arrayOf(
                "-Pokd-project=$OKD_PROJECT",
                "-Pwork-directory=$WORK_DIR",
                "-Pproject-prefix=oc-template-ft"
            )
            additionalEnvVariables = DEFAULT_ENV_VARIABLES
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$WORK_DIR/template.yaml")).exists()
        assertThat(projectPath.resolve("build/$WORK_DIR/logs").toFile().listFiles()).isEmpty()
    }

    /**
     * Verifies that simple REST service can be deployed & the endpoint responds as expected.
     * The service route is retrieved via the plugin & the endpoint check is performed in the project's `build.gradle`.
     */
    @Test
    fun testSimpleRestService() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/rest-service"
            tasks = TASKS
            additionalArguments = DEFAULT_PARAMETERS
            additionalEnvVariables = DEFAULT_ENV_VARIABLES
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$WORK_DIR/template.yaml")).exists()
        assertThat(
            projectPath.resolve("build/$WORK_DIR/logs").toFile().listFiles {
                file -> file.name.startsWith(getLogFileName("simple-rest").removeSuffix(".log"))
            }
        ).hasSize(1);
    }

    @Test
    fun testMissingParameter() {
        val (instance, _) = gradleProcessInstance {
            testProjectName = "projects/simple-project"
            tasks = TASKS
            additionalArguments = arrayOf(
                "-Pokd-project=$OKD_PROJECT",
                "-Pwork-directory=$WORK_DIR",
                "-Pproject-prefix=oc-template-ft"
            )
            additionalEnvVariables = DEFAULT_ENV_VARIABLES
        }
        assertNotEquals(0, instance.exitCode)
        assertThat(instance.stdErr).anySatisfy {
            assertThat(it).contains("parameter DOCKER_REGISTRY is required and must be specified")
        }
    }

    @Test
    fun testNamespaceFromEnv() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/without-pod"
            tasks = TASKS
            additionalArguments = arrayOf(
                "-Pwork-directory=$WORK_DIR",
                "-Pproject-prefix=$DEPLOYMENT_PREFIX"
            )
            additionalEnvVariables = DEFAULT_ENV_VARIABLES + mapOf(
                "OKD_PROJECT" to OKD_PROJECT
            )
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$WORK_DIR/template.yaml")).exists()
        assertThat(projectPath.resolve("build/$WORK_DIR/logs").toFile().listFiles()).isEmpty()
    }

    @Test
    fun testInvalidOKDProject() {
        val (instance, _) = gradleProcessInstance {
            testProjectName = "projects/without-pod"
            tasks = TASKS
            additionalArguments = arrayOf(
                "-Pokd-project=invalid-namespace",
                "-Pwork-directory=$WORK_DIR",
                "-Pproject-prefix=$DEPLOYMENT_PREFIX"
            )
            additionalEnvVariables = DEFAULT_ENV_VARIABLES
        }
        assertNotEquals(0, instance.exitCode)
        assertThat(instance.stdErr).anySatisfy {
            assertThat(it).contains("in the namespace \"invalid-namespace\"")
        }
    }

    @Test
    fun testProjectWithFailedResource() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/failed-resource"
            tasks = TASKS
            additionalArguments = DEFAULT_PARAMETERS + arrayOf("-Pokd-wait-attempts=3")
            additionalEnvVariables = DEFAULT_ENV_VARIABLES
        }
        assertNotEquals(0, instance.exitCode)
        assertThat(instance.stdErr).anySatisfy {
            assertThat(it).contains("Pods readiness check attempts exceeded")
        }
        assertThat(projectPath.resolve("build/$WORK_DIR/template.yaml")).exists()
    }

    @Test
    fun testProjectWithNestedServices() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/nested-services"
            tasks = TASKS
            additionalArguments = DEFAULT_PARAMETERS
            additionalEnvVariables = DEFAULT_ENV_VARIABLES
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$WORK_DIR/service1/template.yaml")).exists()
        assertThat(projectPath.resolve("build/$WORK_DIR/service1/logs/${getLogFileName("postgres-1")}")).exists()
        assertThat(projectPath.resolve("build/$WORK_DIR/service2/template.yaml")).exists()
        assertThat(projectPath.resolve("build/$WORK_DIR/service2/logs/${getLogFileName("postgres-2")}")).exists()
    }

    @Test
    fun testProjectWithNestedServicesMisconfiguration() {
        val (instance, _) = gradleProcessInstance {
            testProjectName = "projects/nested-services-misconfiguration"
            tasks = TASKS
            additionalArguments = DEFAULT_PARAMETERS
            additionalEnvVariables = DEFAULT_ENV_VARIABLES
        }
        assertNotEquals(0, instance.exitCode)
        assertThat(instance.stdErr).anySatisfy {
            assertThat(it).contains("Cannot find registered service 'postgres-2'. It may be disabled or misconfigured")
        }
    }

    private fun getLogFileName(serviceName: String): String {
        return "$DEPLOYMENT_PREFIX-1-0-snapshot-$serviceName.log"
    }

}