# Octopus OC Template Gradle Plugin
This Gradle plugin provides a convenient way to interact with OKD/OpenShift templates in your build process. It wraps common oc CLI commands into easily callable Gradle tasks.

### Tasks
This plugin automatically generates Gradle tasks to manage OpenShift resources based on your registered services & templates:
- `ocProcess` - Processes all registered OpenShift templates with parameters
- `ocCreate` - Creates resources from processed templates for all services
- `ocWaitReadiness` - Waits for pods (defined in services) to become ready
- `ocLogs` - Fetches logs from pods for all services
- `ocDelete` - Deletes all created resources for cleanup

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
    namespace.set("default-namespace") // Default namespace, can be set from env variable: OKD_PROJECT
    workDir.set(layout.buildDirectory.dir("oc-work")) // Stores generated resources/logs, default: build/oc-template

    clusterDomain("apps.ocpd.eq.openmind.org") // Can be set from env variable: OKD_CLUSTER_DOMAIN
    prefix.set("ft") // Deployment prefix
    version.set("1.0.0") // Default to project.version
    
    enabled.set(true) // Enables all ocTemplate (e.g., ocProcess, ocCreate) tasks, default: true
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
        // e.g, ocProcessGiteaServices, ocCreateGiteaServices
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

### Resource Naming Convention
To ensure full support and enable reliable resource discovery, all resources defined in the OpenShift template YAML must include names prefixed with:
```text
${DEPLOYMENT_PREFIX}-<serviceName>
```

This naming convention allows the plugin to correctly locate and manage the generated resources.

#### Example
**Plugin configuration:**
```kotlin
service("postgres") {
    templateFile.set(file("templates/postgres-template.yaml"))
    parameters.set(mapOf(
        "DATABASE_NAME" to "mydb",
        "DATABASE_USER" to "user"
    ))
}
```
Corresponding **postgres-template.yaml** content:
```yaml
apiVersion: v1
kind: Pod
metadata:
    name: ${DEPLOYMENT_PREFIX}-postgres
```
You may add a suffix if needed, e.g., `${DEPLOYMENT_PREFIX}-postgres-app`, as long as the name starts with `${DEPLOYMENT_PREFIX}-postgres`.

## Development
### Running Functional Tests (FT)
To run the functional tests, ensure the following properties or environment variables are set:
- `docker.registry` or `DOCKER_REGISTRY`
- `okd.project` or `OKD_PROJECT`
- `okd.cluster-domain` or `OKD_CLUSTER_DOMAIN`
