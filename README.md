# octopus-oc-template-gradle-plugin
This Gradle plugin provides a convenient way to interact with OKD/OpenShift templates in your build process. It wraps common oc CLI commands into easily callable Gradle tasks.

### Features
- Initialize and process OpenShift templates with parameters
- Create resources from templates
- Wait for pods to be ready
- Fetch logs from pods
- Clean up by deleting the created resources

### Usage
#### Apply the Plugin

```kotlin
plugins {
   id("org.octopusden.octopus.oc-template")
}
```
#### Register a Template Service
```kotlin
val testService = ocTemplateService.register("testService") {
   namespace = "your-okd-namespace"             // Target OKD project namespace
   templateFile = file("path/to/template.yaml") // Template file path
   templateParameters.put("KEY", "value")       // Parameters for the template
   workDirectory.set(layout.buildDirectory.dir("work-directory"))
}
```

#### Use in Tasks
You can trigger the OpenShift actions in your tasks:
```kotlin
tasks.named("build") {
   doLast {
      testService.get().create()               // Create resources
      testService.get().waitPodsForReady()     // Wait for pods to be ready
      testService.get().logs("your-pod-name")  // Print logs
      testService.get().delete()               // Clean up
   }
}
```