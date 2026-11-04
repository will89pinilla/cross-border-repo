plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "com.cobre"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgresql)
    implementation(libs.springdoc.webmvc.ui)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit.junit6)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Fixed name for the Dockerfile (spec §11): it does not change with `version`.
tasks.bootJar {
    archiveFileName = "accounts-service.jar"
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark")
    }
}

// Spec §12.1: F07 with 100,000 events and 1,000 accounts at max-concurrency 1, 4 and 16, plus the
// reader's heap at the limits of §6.2. Never part of `build`. Heap: -Pbenchmark.xmx=2g (default).
tasks.register<Test>("benchmark") {
    description = "Runs the F07 benchmark and the reader memory measure (spec §12.1, §6.2)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("benchmark")
    }
    maxHeapSize = providers.gradleProperty("benchmark.xmx").getOrElse("2g")
    systemProperty("benchmark.reportDir", layout.buildDirectory.dir("reports/benchmark").get().asFile.absolutePath)
    testLogging {
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}
