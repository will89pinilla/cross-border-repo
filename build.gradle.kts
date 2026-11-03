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
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit.junit6)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
}
