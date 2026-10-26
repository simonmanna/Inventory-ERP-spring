plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Release signing comes from the environment (CI secrets) or ~/.gradle/gradle.properties,
// never from the repo: POS_KEYSTORE_FILE, POS_KEYSTORE_PASSWORD, POS_KEY_ALIAS, POS_KEY_PASSWORD.
fun secret(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: (findProperty(name) as String?)?.takeIf { it.isNotBlank() }

val releaseKeystore = secret("POS_KEYSTORE_FILE")?.let { file(it) }

// versionCode must grow with every build handed to a device; CI passes -Ppos.versionCode=<run number>.
// Locally it falls back to the commit count, which only grows on main.
val posVersionCode = (findProperty("pos.versionCode") as String?)?.toInt()
    ?: providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
val posVersionName = (findProperty("pos.versionName") as String?) ?: "0.2.0"

android {
    namespace = "com.poscafe.pos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.poscafe.pos"
        minSdk = 26
        targetSdk = 36
        versionCode = posVersionCode
        versionName = posVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Public cleartext HTTP to the server; see ServerUrlPolicy. Off unless -Ppos.allowPublicCleartext=true.
        buildConfigField(
            "boolean", "ALLOW_PUBLIC_CLEARTEXT",
            ((findProperty("pos.allowPublicCleartext") as String?) == "true").toString(),
        )
    }

    // `-Ppos.testBuildType=release` builds the instrumented tests against the
    // signed, minified release so the device E2E suite can drive what ships.
    testBuildType = (findProperty("pos.testBuildType") as String?) ?: "debug"

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = secret("POS_KEYSTORE_PASSWORD")
                keyAlias = secret("POS_KEY_ALIAS")
                keyPassword = secret("POS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_PUBLIC_CLEARTEXT", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
            if (testBuildType == "release") proguardFiles("proguard-release-under-test.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Existing findings are frozen here; new ones fail the build.
        baseline = file("lint-baseline.xml")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets {
        // Exported Room schemas, so migration tests can open every historic version.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// An unsigned release APK cannot be installed; fail before any work instead of producing one.
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.project == project && it.name in setOf("assembleRelease", "bundleRelease") }
    check(!wantsRelease || releaseKeystore != null) {
        "Release signing is not configured: set POS_KEYSTORE_FILE, POS_KEYSTORE_PASSWORD, " +
            "POS_KEY_ALIAS and POS_KEY_PASSWORD (env or ~/.gradle/gradle.properties)."
    }
}

// Room schema history — lets migrations be checked against generated DDL.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    // SQLCipher/Windows CI quirk: Room's DB verifier needs a writable temp dir
    // for its bundled SQLite; default to the user temp dir so `gradlew` builds
    // don't fail with "temp dir needs to be readable/writable/executable".
    arg("room.incremental", "true")
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Inter via the Google Fonts provider (falls back to system font offline).
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Menu photos (best-effort over LAN; placeholder when offline).
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Room (encrypted with SQLCipher)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // Background sync
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Offline PIN verification against the synced bcrypt hash.
    implementation("at.favre.lib:bcrypt:0.10.2")

    // Barcode scanning (ML Kit + CameraX)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")

    // Encrypted key storage for the DB passphrase + device token.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Room + Android framework on the JVM, so sync and auth run without a device.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // Compose UI tests on the JVM (Robolectric native graphics renders real pixels).
    testImplementation(platform("androidx.compose:compose-bom:2024.09.02"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    // Real-device end-to-end tests (PosDeviceE2ETest) drive the installed app through its UI.
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
