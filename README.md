# octopus-oc-template-gradle-plugin
This Gradle plugin provides a convenient way to interact with OKD/OpenShift templates in your build process. It wraps common oc CLI commands into easily callable Gradle tasks.

### Tasks
- `ocTemplateProcess` initialize and process OpenShift templates with parameters
- `ocTemplateCreate` create resources from templates
- `ocTemplateWaitReadiness` wait for pods to be ready
- `ocTemplateLogs` fetch logs from pods
- `ocTemplateDelete` clean up by deleting the created resources

### Usage
#### Apply the Plugin

```kotlin
plugins {
   id("org.octopusden.octopus.oc-template")
}
```

#### Register a Template Service
```kotlin
ocTemplate {
    namespace.set("default-namespace")  // Default namespace for all services
    workDirectory.set(layout.buildDirectory.dir("oc-work"))  // Default work directory

    enabled.set(true)
    isRequiredBy("test")
    
    // Optional fields
    period.set(1200L)  // Period checking resource readiness
    attempts.set(20)   // Attempts checking resource readiness

    service("database") {
        templateFile.set(file("templates/database-template.yaml"))
        podNames.set(listOf("pod1", "pod2")) // Pods created from the template
        parameters.set(mapOf(
            "DATABASE_NAME" to "mydb",
            "DATABASE_USER" to "user"
        ))
    }

    service("backend") {
        templateFile.set(file("templates/backend-template.yaml"))
        dependsOn("database")  // Declare dependency on another service
    }
}
```

```kotlin
ocTemplate {
    namespace.set("default-namespace")  // Default namespace for all services
    workDirectory.set(layout.buildDirectory.dir("oc-work"))  // Default work directory

    isRequiredBy("test")
    
    // Optional fields
    period.set(1200L)  // Period checking resource readiness
    attempts.set(20)   // Attempts checking resource readiness

    val testProfile = project.findProperty("testProfile") as String? ?: "bitbucket"

    giteaServices {
        enabled.set(testProfile == "gitea")

        // Override default settings
        namespace.set("gitea-namespace")
        period.set(2400L)
        attempts.set(50)

        service("gitea") {
            templateFile.set(file("templates/gitea-template.yaml"))
        }
        service("opensearch") {
            templateFile.set(file("templates/opensearch-template.yaml"))
            namespace.set("opensearch-namespace")
        }
    }
    
    bitbucketServices {
        enabled.set(testProfile == "bitbucket")
        
        service("bitbucket") {
            templateFile.set(file("templates/bitbucket-template.yaml"))
        }
    }
    
    service("vcs-facade") {
        templateFile.set(file("templates/vcs-facade-template.yaml"))
        podNames.set("vcs-facade")
        if (testProfile == "bitbucket") {
            dependsOn("bitbucket")
        } else {
            dependsOn("gitea", "opensearch")
        }
    }
}
```

#### Use in Tasks
You can depends the OpenShift actions in your tasks:
```kotlin
ocTemplate.isRequiredBy(test)
```