plugins {
    // Auto-provisions the Java 25 toolchain when it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "inventory-erp-license-server"

// One pinned stack for the whole product: the ERP's version catalogue is the single source of truth.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../backend/gradle/libs.versions.toml"))
        }
    }
}
