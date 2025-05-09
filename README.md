# Octopus OC Template Gradle Plugin
This Gradle plugin provides a convenient way to interact with OKD/OpenShift templates in your build process. It wraps common oc CLI commands into easily callable Gradle tasks.

### Tasks
This plugin automatically generates Gradle tasks to manage OpenShift resources based on your registered services & templates:
- `ocProcessAll` - Processes all registered OpenShift templates with parameters
- `ocCreateAll` - Creates resources from processed templates for all services
- `ocWaitReadinessAll` - Waits for pods (defined in services) to become ready
- `ocLogsAll` - Fetches logs from pods for all services
- `ocDeleteAll` - Deletes all created resources for cleanup

### Getting Started
#### Apply the Plugin
```kotlin
plugins {
   id("org.octopusden.octopus.oc-template")
}
```

### Configuration Example
```kotlin
ocTemplate {
    namespace.set("default-namespace") // Default namespace, can be set from env variable: OKD_NAMESPACE
    workDir.set(layout.buildDirectory.dir("oc-work")) // Stores generated resources/logs, default: build/oc-template

    enabled.set(true) // Enables all ocTemplate (e.g., ocProcessAll, ocCreateAll) tasks, default: true
    isRequiredBy(tasks.named("test")) // Ensures resources from registered services are ready before "test" runs

    // Optional pods readiness settings
    period.set(1200L)  // Delay (ms) between readiness checks, default: 15000L
    attempts.set(20)   // Max number of check attempts, default: 20

    // Register services
    service("database") {
        templateFile.set(file("templates/database-template.yaml"))
        parameters.set(mapOf(
            "DATABASE_NAME" to "mydb",
            "DATABASE_USER" to "user"
        ))
    }
    
    service("backend") {
        templateFile.set(file("templates/backend-template.yaml"))
        parameters.set(mapOf(
            "USER" to "user"
        ))
        
        // Required to declare the pod names if the template generate pods resources, will be used for pods readiness check
        // Use "template.alpha.openshift.io/wait-for-ready" annotation if the template doesn't create any pod resource
        pods.set(listOf("backend-pod")) 
        
        // Declared dependencies to another services
        // Ensures that database resource is ready before creating backend resource
        dependsOn.set(listOf("database"))
        
    }
}
```

#### Grouping Services
Use nested groups when a set of services shares the same configuration but differs from the global setup:
```kotlin
ocTemplate {
    // Group services with same configuration
    group("giteaServices").apply {
        // If enabled, ocTemplate tasks for giteaServices will be registered 
        // e.g, ocProcessAllGiteaServices, ocCreateAllGiteaServices
        enabled.set(testProfile == "gitea")

        // Override the global settings within this group
        namespace.set("gitea-namespace")
        period.set(24000L)
        attempts.set(50)

        service("gitea") {
            templateFile.set(file("templates/gitea-template.yaml"))
        }

        service("opensearch") {
            templateFile.set(file("templates/opensearch-template.yaml"))
            dependsOn("gitea")
        }
    }
}
```
<details>
<summary>Groovy</summary>

```groovy
ocTemplate {
    giteaServices {
        enabled.set(testProfile == "gitea")
    }
}
```

</details>
