plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.spotless)
}

group = "com.inventoryerp"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Security patch levels over the Spring Boot BOM — keep in step with backend/build.gradle.kts.
extra["tomcat.version"] = "11.0.25"
extra["jackson-bom.version"] = "3.1.7"
extra["jackson-2-bom.version"] = "2.21.7"

dependencies {
    // Web / security
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(libs.springdoc)

    // Persistence
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")

    // Ops
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.logstash)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)
    implementation(libs.bucket4j)
    implementation(libs.totp)

    // Renewal reminders
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.archunit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    systemProperty("user.timezone", "UTC")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports { xml.required = true; html.required = true }
}

// Same quality gate as the ERP: the use-case package carries the rules, so it carries the coverage.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("com.inventoryerp.license.application")
            limit {
                counter = "LINE"
                minimum = (findProperty("coverage.application.min") as String? ?: "0.80").toBigDecimal()
            }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

spotless {
    java {
        target("src/**/*.java")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
}

tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
    includeConfigs.set(listOf("runtimeClasspath"))
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/license-server-sbom.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx/license-server-sbom.xml"))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "license-server.jar"
}
