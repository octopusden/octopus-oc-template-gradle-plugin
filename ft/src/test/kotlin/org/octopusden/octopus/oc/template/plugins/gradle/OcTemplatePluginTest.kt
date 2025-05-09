package org.octopusden.octopus.oc.template.plugins.gradle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals

class OcTemplatePluginTest {

    companion object {
        const val DEFAULT_WORK_DIR = "okd"
        val DEFAULT_TASKS = arrayOf("clean", "build", "--info", "--stacktrace")
        val DEFAULT_OKD_NAMESPACE: String = System.getenv().getOrDefault("OKD_NAMESPACE", "test-env")
        val DOCKER_REGISTRY: String = System.getenv()["DOCKER_REGISTRY"].toString()
    }

    @Test
    fun testSimpleProject() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/project-template"
            templateYamlFileName = "templates/postgres-db.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pokd-namespace=$DEFAULT_OKD_NAMESPACE",
                "-Pwork-directory=$DEFAULT_WORK_DIR",
                "-Pokd-pod-name=postgres-db",
                "-Pdocker-registry=$DOCKER_REGISTRY",
            )
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/postgres-db.yaml")).exists()
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/logs/postgres-db.log")).exists()
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
                "-Pdocker-registry=$DOCKER_REGISTRY",
            )
        }
        assertNotEquals(0, instance.exitCode)
        assertThat(instance.stdErr).anySatisfy {
            assertThat(it).contains("parameter POD_NAME is required and must be specified")
        }
    }

    @Test
    fun testSimpleProjectWithoutPod() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/without-pod"
            templateYamlFileName = "templates/simple-pvc.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pokd-namespace=$DEFAULT_OKD_NAMESPACE",
                "-Pwork-directory=$DEFAULT_WORK_DIR"
            )
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/simple-pvc.yaml")).exists()
    }

    @Test
    fun testNamespaceFromEnv() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/without-pod"
            templateYamlFileName = "templates/simple-pvc.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pwork-directory=$DEFAULT_WORK_DIR"
            )
            additionalEnvVariables = mapOf("OKD_NAMESPACE" to DEFAULT_OKD_NAMESPACE)
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/simple-pvc.yaml")).exists()
    }

    @Test
    fun testInvalidOKDNamespace() {
        val (instance, _) = gradleProcessInstance {
            testProjectName = "projects/without-pod"
            templateYamlFileName = "templates/simple-pvc.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pokd-namespace=invalid-namespace",
                "-Pwork-directory=$DEFAULT_WORK_DIR"
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
            additionalArguments = arrayOf(
                "-Pokd-namespace=$DEFAULT_OKD_NAMESPACE",
                "-Pwork-directory=$DEFAULT_WORK_DIR",
                "-Pokd-pod-name=failed-pod",
                "-Pdocker-registry=$DOCKER_REGISTRY",
                "-Pokd-wait-attempts=3",
            )
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
            additionalArguments = arrayOf(
                "-Pokd-namespace=$DEFAULT_OKD_NAMESPACE",
                "-Pwork-directory=$DEFAULT_WORK_DIR",
                "-Pdocker-registry=$DOCKER_REGISTRY",
            )
        }
        assertEquals(0, instance.exitCode)
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/service1/postgres-db.yaml")).exists()
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/service1/logs/postgres-db-1.log")).exists()
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/service2/postgres-db.yaml")).exists()
        assertThat(projectPath.resolve("build/$DEFAULT_WORK_DIR/service2/logs/postgres-db-2.log")).exists()
    }

    @Test
    fun testProjectWithNestedServicesMisconfiguration() {
        val (instance, projectPath) = gradleProcessInstance {
            testProjectName = "projects/nested-services-misconfiguration"
            templateYamlFileName = "templates/postgres-db.yaml"
            tasks = DEFAULT_TASKS
            additionalArguments = arrayOf(
                "-Pokd-namespace=$DEFAULT_OKD_NAMESPACE",
                "-Pwork-directory=$DEFAULT_WORK_DIR",
                "-Pdocker-registry=$DOCKER_REGISTRY",
            )
        }
        assertNotEquals(0, instance.exitCode)
        assertThat(instance.stdErr).anySatisfy {
            assertThat(it).contains("Cannot find registered service 'postgres-db-2'. It may be disabled or misconfigured")
        }
    }
}