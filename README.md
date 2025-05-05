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

#### Basic Usage
```kotlin
ocTemplate {
    namespace.set("default-namespace") // Default namespace for all services
    workDirectory.set(layout.buildDirectory.dir("oc-work")) // Directory for processed resources and logs

    enabled.set(true) // Default to true (creating all ocTemplate tasks, e.g., ocTemplateProcess, ocTemplateCreate)
    isRequiredBy("test") // Ensure all declared services are prepared before running the "test" task

    // Optional readiness check settings
    period.set(1200L)  // Delay (ms) between pod readiness checks
    attempts.set(20)   // Max number of readiness check attempts

    service("database") {
        templateFile.set(file("templates/database-template.yaml"))
        parameters.set(mapOf(
            "DATABASE_NAME" to "mydb",
            "DATABASE_USER" to "user"
        ))
    }

    service("backend") {
        templateFile.set(file("templates/backend-template.yaml"))
        podNames.set("backend-pod")  // Explicitly define pod name(s) created from template
        dependsOn("database")  // Service dependency
    }
}
```

#### Advance Usage
```kotlin
ocTemplate {
    namespace.set("default-namespace") 
    workDirectory.set(layout.buildDirectory.dir("oc-work"))

    isRequiredBy("test")

    val testProfile = project.findProperty("testProfile") as String? ?: "bitbucket"

    // Group of services configuration
    // All parameters from ocTemplate can be overridden on group nested service configurations
    giteaServices {
        // If enabled, ocTemplate tasks for giteaServices will be registered 
        // e.g, ocTemplateProcessGiteaServices, ocTemplateCreateGiteaServices
        enabled.set(testProfile == "gitea")

        // Override global settings within this group
        namespace.set("gitea-namespace")
        period.set(2400L)
        attempts.set(50)

        service("gitea") {
            templateFile.set(file("templates/gitea-template.yaml"))
        }
        
        service("opensearch") {
            templateFile.set(file("templates/opensearch-template.yaml"))
            dependsOn("gitea")
            
            // All parameters from ocTemplate/giteaServices can be overridden on service configuration
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
        if (testProfile == "bitbucket") {
            dependsOn("bitbucket")
        } else {
            dependsOn("gitea", "opensearch")
        }
    }
}
```
