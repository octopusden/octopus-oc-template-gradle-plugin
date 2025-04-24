plugins {
    kotlin("jvm")
}

group = "org.octopusden.octopus"

dependencies {
    api("com.platformlib:platformlib-process-local:${project.extra["platformlib-process.version"]}")
    testImplementation("ch.qos.logback:logback-classic:${project.extra["logback.version"]}")
    testImplementation("org.assertj:assertj-core:${project.extra["assertj.version"]}")
    testImplementation(platform("org.junit:junit-bom:${project.extra["junit-jupiter.version"]}"))
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}

tasks.test {
    useJUnitPlatform()
}