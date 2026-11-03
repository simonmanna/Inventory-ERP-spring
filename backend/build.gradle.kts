plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.spotless)
    alias(libs.plugins.pitest)
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

// Security patch levels over the Spring Boot BOM (Trivy findings, P8). Drop each line once Boot ships the fix.
extra["tomcat.version"] = "11.0.25" // CVE fixes in tomcat-embed-core 11.0.24 (CRITICAL)
extra["jackson-bom.version"] = "3.1.7" // tools.jackson DoS fixes (CVE-2026-89407, -89425, -68497, -91776, -91777)
extra["jackson-2-bom.version"] = "2.21.7" // com.fasterxml.jackson 2.x (transitive) — same fixes

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${libs.versions.spring.modulith.get()}")
    }
}

dependencies {
    // Web / security
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(libs.springdoc)

    // Persistence
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")

    // Modularity + events (JDBC event publication registry = transactional outbox)
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
    runtimeOnly("org.springframework.modulith:spring-modulith-observability")

    // Ops
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.logstash)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)
    implementation(libs.bucket4j)

    // Messaging / documents
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation(libs.openpdf)
    implementation(libs.zxing)
    implementation(libs.commons.csv)
    implementation(libs.poi.ooxml)
    // Only the Apache-client PushService is used; the AsyncHttpClient-based PushAsyncService (and its CVEs) is not.
    implementation(libs.web.push) { exclude(group = "org.asynchttpclient") }
    constraints {
        implementation("org.bitbucket.b_c:jose4j:0.9.6") { because("CVE-2023-31582, CVE-2024-29371 in 0.7.9 via web-push") }
    }
    implementation(libs.bouncycastle)
    implementation(libs.totp)

    // Mapping
    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.archunit)
    testImplementation(libs.rest.assured)
    testImplementation(libs.instancio)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Amapstruct.defaultComponentModel=spring"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // ~150 test classes with many cached Spring contexts outgrow the 512 MB Gradle default.
    maxHeapSize = "3g"
    // Application.main pins the JVM (and so the PgJDBC session TimeZone) to UTC; @SpringBootTest never runs main.
    systemProperty("user.timezone", "UTC")
}

tasks.test {
    useJUnitPlatform { excludeTags("perf", "acc-known-defect") }
    finalizedBy(tasks.jacocoTestReport)
}

// POS checkout latency / SQL count (PosCheckoutPerfIT). Not part of `build`: timings depend on the box.
val perfTest by tasks.registering(Test::class) {
    description = "Runs @Tag(\"perf\") tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
    testLogging { showStandardStreams = true }
    listOf("pos.checkout.assert", "pos.checkout.dumpSql", "pos.checkout.dumpTail", "pos.checkout.traceSql",
        "pos.checkout.p95.ms", "pos.checkout.prodRttMs",
        "pos.checkout.runs", "pos.checkout.warmup").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    System.getProperty("perf.jfr")?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
}

// ACC-0 accounting verification (docs/accounting/ACCOUNTING-TEST-STRATEGY.md): @Tag("acc-known-defect") tests assert
// the correct accounting outcome for a reproduced defect, so they fail until their ACC phase fixes it. Excluded from
// test / fastTest / accountingRegression; run here, failures reported but not fatal. Zero discovered tests IS fatal
// (a silently empty run must not look green). Fresh DB container (shared reusable one carries other branches' schema).
val acc0VerificationTest by tasks.registering(Test::class) {
    description = "Runs @Tag(\"acc-known-defect\") accounting verification tests (failures reported, not fatal)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath.filter {
        !it.name.startsWith("spring-modulith-observability") && !it.name.startsWith("spring-modulith-actuator")
    }
    useJUnitPlatform { includeTags("acc-known-defect") }
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
    ignoreFailures = true
    outputs.upToDateWhen { false }
    filter { isFailOnNoMatchingTests = true }
    reports { junitXml.required = true; html.required = true }
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ d, r ->
        if (d.parent == null) {
            println("acc0VerificationTest: ${r.testCount} run, ${r.successfulTestCount} passed, " +
                "${r.failedTestCount} failed, ${r.skippedTestCount} skipped")
            if (r.testCount == 0L) throw GradleException("acc0VerificationTest discovered no tests")
        }
    }))
}

// Inner dev loop (`./gradlew fastTest [--tests X]`): no JaCoCo, C1-only JIT for faster Spring startup, the
// Testcontainers Postgres kept alive between runs (TESTCONTAINERS_REUSE_ENABLE), and the multi-minute seed /
// architecture suites skipped. NOT the gate: `./gradlew build` still runs everything on a fresh container.
// Reset the reused DB: docker rm -f $(docker ps -q --filter label=org.testcontainers.reuse.hash)
val fastTest by tasks.registering(Test::class) {
    description = "Fast inner-loop tests: reused DB container, no coverage, slow suites skipped."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    // Modulith actuator/observability analyse every class with ArchUnit at startup (~20 s blocked per context).
    classpath = sourceSets.test.get().runtimeClasspath.filter {
        !it.name.startsWith("spring-modulith-observability") && !it.name.startsWith("spring-modulith-actuator")
    }
    useJUnitPlatform { excludeTags("perf", "acc-known-defect") }
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
    jvmArgs("-XX:TieredStopAtLevel=1")
    environment("TESTCONTAINERS_REUSE_ENABLE", "true")
    outputs.upToDateWhen { false }
    filter {
        isFailOnNoMatchingTests = false
        listOf("*ConstructionDemoSeedIT", "*PosLakeviewSimIT", "*ManufacturingDemoSeedIT", "*FleetDemoSeedIT",
            "*ArchitectureTests", "*ModularityTests").forEach { excludeTestsMatching(it) }
    }
}

// Accounting regression suite (Cost Management CM-0, plan Part F2): the costing / valuation / close integration tests
// that guard GL integrity. Grows phase by phase; run before every costing merge: `./gradlew accountingRegression`.
val accountingRegression by tasks.registering(Test::class) {
    description = "Accounting & period-close regression suite (costing, valuation, landed cost, allocation)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath.filter {
        !it.name.startsWith("spring-modulith-observability") && !it.name.startsWith("spring-modulith-actuator")
    }
    useJUnitPlatform { excludeTags("perf", "acc-known-defect") }
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
    environment("TESTCONTAINERS_REUSE_ENABLE", "true")
    outputs.upToDateWhen { false }
    filter {
        listOf("*LandedCostVoucherIT", "*StandardProcessCostingIT", "*MultiStageProductionIT", "*QuarryIT",
            "*CrushingIT", "*PrecastManufacturingIT", "*FleetCostingIT", "*CostCenterAccountingIT",
            "*AdvancedCostingIT", "*InventoryCostPolicyIT", "*ManufacturingCostingIT", "*ReportingIT",
            "*AbsorptionSettlementCausesIT", "*QuarryRehabMovementsIT",
            "*ImportLandedScenarioIT", "*DistributionBranchScenarioIT",
            "*ProjectCostScenarioIT", "*ContractRevenueScenarioIT", "*ProfitabilityReconciliationIT",
            "*CostCloseIT", "*SimulationIsolationIT", "*ReportEngineIT",
            "*FinancialReportIntegrityIT", "*AllReportsRunIT",
            "*LandedCostAuditIT", "*CostingAuditIT", "*SharedFxIT",
            "*Acc0FinancialReportingIT", "*Acc0FxIT", "*Acc0ReversalIT", "*Acc0MoneyPrecisionIT",
            "*Acc0IdempotencyIT", "*Acc0PayrollPrecisionTest",
            "com.inventoryerp.costing.*").forEach { includeTestsMatching(it) }
    }
}

tasks.jacocoTestReport {
    reports { xml.required = true; html.required = true }
}

// Quality gate (P8): line coverage of the application (use-case) packages. The floor is a ratchet — raise
// `coverage.application.min` in gradle.properties as coverage grows; never lower it.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("com.inventoryerp.*.application")
            limit {
                counter = "LINE"
                minimum = (findProperty("coverage.application.min") as String? ?: "0.80").toBigDecimal()
            }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

// Formatting gate: whitespace / line-ending hygiene on every Java source (CI runs spotlessCheck).
spotless {
    java {
        target("src/**/*.java")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
}

// Mutation testing (P8) on the money / quantity calculators — not part of `build`; CI runs it nightly.
pitest {
    pitestVersion = libs.versions.pitest.asProvider().get()
    junit5PluginVersion = libs.versions.pitest.junit5.get()
    addJUnitPlatformLauncher = false // Boot 4 brings JUnit Platform 6; the plugin would pin 1.x
    targetClasses = listOf(
        "com.inventoryerp.pos.application.PricingPolicy*",
        "com.inventoryerp.pos.application.OrderMutationPolicy*",
        "com.inventoryerp.kernel.common.*",
        "com.inventoryerp.invoicing.application.TaxCalculationService*",
        "com.inventoryerp.inventory.application.CostResolver*",
        "com.inventoryerp.inventory.application.StockPosting*",
        "com.inventoryerp.accounting.application.*Calculator*",
    )
    targetTests = listOf(
        "com.inventoryerp.pos.PosUnitTest*",
        "com.inventoryerp.kernel.common.*Test*",
        "com.inventoryerp.invoicing.TaxCalculationServiceTest*",
        "com.inventoryerp.inventory.InventoryUnitTest*",
        "com.inventoryerp.accounting.AccountingUnitTest*",
        "com.inventoryerp.accounting.AccountingSpecsTest*",
    )
    threads = 4
    outputFormats = listOf("HTML", "XML")
    timestampedReports = false
    mutationThreshold = (findProperty("pitest.mutation.min") as String? ?: "0").toInt()
}

// CycloneDX SBOM of the runtime classpath (attached to releases).
tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
    includeConfigs.set(listOf("runtimeClasspath"))
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/inventory-erp-sbom.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx/inventory-erp-sbom.xml"))
}

// Regenerates Permissions.java / DomainEvents.java from packages/shared (the TS source of truth).
tasks.register<Exec>("generateContracts") {
    group = "codegen"
    description = "Generate Java constants from packages/shared (permissions, events)."
    workingDir = rootDir.parentFile
    commandLine("node", "tools/contracts2java/generate.mjs")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "inventory-erp.jar"
}
