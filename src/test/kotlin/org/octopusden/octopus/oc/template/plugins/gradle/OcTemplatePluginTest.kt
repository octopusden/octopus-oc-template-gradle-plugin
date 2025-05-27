package org.octopusden.octopus.oc.template.plugins.gradle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.octopusden.octopus.oc.template.plugins.gradle.runner.gradleProcessInstance

class OcTemplatePluginTest {

    companion object {
        const val DEFAULT_WORK_DIR = "okd"
        const val DEFAULT_PROJECT_PREFIX = "oc-template-ft"
        private val DEFAULT_TASKS = arrayOf("clean", "build", "--info", "--stacktrace")
        private val DEFAULT_OKD_NAMESPACE: String = System.getProperty("okdNamespace")
        private val DOCKER_REGISTRY: String = System.getProperty("dockerRegistry")
        private val DEFAULT_PARAMETERS = arrayOf(
            "-Pokd-namespace=$DEFAULT_OKD_NAMESPACE",
            "-Pwork-directory=$DEFAULT_WORK_DIR",
            "-Pproject-prefix=$DEFAULT_PROJECT_PREFIX",
            "-Pdocker-registry=$DOCKER_REGISTRY"
        )
    }

    @Test
    fun testSimpleProject() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/project-template"
            templateYamlFileName = "templates/postgres-db.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = DEFAULT_PARAMETERS
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/postgres-db.yaml")).exists()
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/logs/${getLogFileName("postgres")}")).exists()
    }

    @Test
    fun testSimpleProjectWithoutPod() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/without-pod"
            templateYamlFileName = "templates/simple-pvc.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pokd-namespace=$DEFAULT_OKD_NAMESPACE",
                "-Pwork-directory=$DEFAULT_WORK_DIR",
                "-Pproject-prefix=oc-template-ft"
            )
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/simple-pvc.yaml")).exists()
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/logs").toFile().listFiles()).isEmpty()
    }

    /**
     * Verifies that simple REST service can be deployed & the endpoint responds as expected.
     * The service route is retrieved via the plugin & the endpoint check is performed in the project's `build.gradle`.
     */
    @Test
    fun testSimpleRestService() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/rest-service"
            templateYamlFileName = "templates/rest-service.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = DEFAULT_PARAMETERS
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/rest-service.yaml")).exists()
        assertThat(
            projectPath.resolve("build/$DEFAULT_WORK_DIR/logs").toFile().listFiles {
                    file -> file.name.startsWith(getLogFileName("simple-rest").removeSuffix(".log"))
            }
        ).hasSize(1);
    }

    @Test
    fun testMissingParameter() {
        val (instance, _) = gradleProcessInstance {
            testProjectName = "projects/project-template"
            templateYamlFileName = "templates/postgres-db.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pokd-namespace=$DEFAULT_OKD_NAMESPACE",
                "-Pwork-directory=$DEFAULT_WORK_DIR",
                "-Pproject-prefix=oc-template-ft"
            )
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
            templateYamlFileName = "templates/simple-pvc.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pwork-directory=$DEFAULT_WORK_DIR",
                "-Pproject-prefix=$DEFAULT_PROJECT_PREFIX"
            )
            additionalEnvVariables = mapOf(
                "OKD_NAMESPACE" to DEFAULT_OKD_NAMESPACE
            )
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/simple-pvc.yaml")).exists()
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/logs").toFile().listFiles()).isEmpty()
    }

    @Test
    fun testInvalidOKDNamespace() {
        val (instance, _) = gradleProcessInstance {
            testProjectName = "projects/without-pod"
            templateYamlFileName = "templates/simple-pvc.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pokd-namespace=invalid-namespace",
                "-Pwork-directory=$DEFAULT_WORK_DIR",
                "-Pproject-prefix=$DEFAULT_PROJECT_PREFIX"
            )
        }
        assertNotEquals(0, instance.exitCode)
        assertThat(instance.stdErr).anySatisfy {
            assertThat(it).contains("in the namespace \"invalid-namespace\"")
        }
    }

    @Test
    fun testProjectWithFailedResource() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/project-template"
            templateYamlFileName = "templates/failed-resource.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = DEFAULT_PARAMETERS + arrayOf("-Pokd-wait-attempts=3")
        }
        assertNotEquals(0, instance.exitCode)
        assertThat(instance.stdErr).anySatisfy {
            assertThat(it).contains("Pods readiness check attempts exceeded")
        }
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/failed-resource.yaml")).exists()
    }

    @Test
    fun testProjectWithNestedServices() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/nested-services"
            templateYamlFileName = "templates/postgres-db.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = DEFAULT_PARAMETERS
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/service1/postgres-db.yaml")).exists()
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/service1/logs/${getLogFileName("postgres-1")}")).exists()
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/service2/postgres-db.yaml")).exists()
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/service2/logs/${getLogFileName("postgres-2")}")).exists()
    }

    @Test
    fun testProjectWithNestedServicesMisconfiguration() {
        val (instance, _) = gradleProcessInstance {
            testProjectName = "projects/nested-services-misconfiguration"
            templateYamlFileName = "templates/postgres-db.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = DEFAULT_PARAMETERS
        }
        assertNotEquals(0, instance.exitCode)
        assertThat(instance.stdErr).anySatisfy {
            assertThat(it).contains("Cannot find registered service 'postgres-2'. It may be disabled or misconfigured")
        }
    }

    private fun getLogFileName(serviceName: String): String {
        return "$DEFAULT_PROJECT_PREFIX-1-0-snapshot-$serviceName.log"
    }
}