import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// One source of truth for the version: version.properties (semver). versionCode = major*10000 + minor*100 + patch.
val appVersion: String = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
    .getProperty("version") ?: error("version.properties: no `version`")
val appVersionCode: Int = run {
    val parts = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersion)?.groupValues?.drop(1)?.map(String::toInt)
        ?: error("version.properties: `$appVersion` is not major.minor.patch")
    val (major, minor, patch) = parts
    require(minor < 100 && patch < 100) { "version.properties: minor and patch must be < 100" }
    major * 10000 + minor * 100 + patch
}

// Release signing: credentials only from Gradle properties (~/.gradle/gradle.properties), never from the repo.
// Without them release builds fail (checkReleaseSigning below); they never fall back to the debug key.
val signingProps = listOf("LUWIFY_STORE_FILE", "LUWIFY_STORE_PASSWORD", "LUWIFY_KEY_ALIAS", "LUWIFY_KEY_PASSWORD")
    .associateWith { providers.gradleProperty(it).orNull?.takeIf(String::isNotBlank) }
val missingSigningProps: List<String> = signingProps.filterValues { it == null }.keys.toList()
val storeFileMissing: Boolean = signingProps["LUWIFY_STORE_FILE"]?.let { !file(it).isFile } ?: false

android {
    namespace = "de.luwify.stundenplanmd"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.luwify.stundenplanmd"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            if (missingSigningProps.isEmpty()) {
                storeFile = file(signingProps.getValue("LUWIFY_STORE_FILE")!!)
                storePassword = signingProps.getValue("LUWIFY_STORE_PASSWORD")
                keyAlias = signingProps.getValue("LUWIFY_KEY_ALIAS")
                keyPassword = signingProps.getValue("LUWIFY_KEY_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        debug {
            // Installs beside the real app (own data), for tests with fake data. Never test on de.luwify.stundenplanmd.
            applicationIdSuffix = ".preview"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "de")
    }
}

// `./gradlew testDebugUnitTest` should also run the pure-JVM tests of :core.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { dependsOn(":core:test") }

// Release packaging needs the release key: fail clearly instead of producing an unsigned or debug-signed APK.
// Names only in the message, never values.
val checkReleaseSigning by tasks.registering {
    val missing = missingSigningProps
    val noFile = storeFileMissing
    doLast {
        if (missing.isNotEmpty()) throw GradleException(
            "Release signing not configured: missing Gradle properties ${missing.joinToString()} " +
                "(expected in ~/.gradle/gradle.properties). Debug builds don't need them.",
        )
        if (noFile) throw GradleException("Release signing: LUWIFY_STORE_FILE does not point to a file.")
    }
}
tasks.matching { it.name == "preReleaseBuild" || it.name.startsWith("packageRelease") || it.name == "validateSigningRelease" }
    .configureEach { dependsOn(checkReleaseSigning) }

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    // QR scan and display: CameraX + ZXing core (Apache 2.0); no ML Kit, no Play Services.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.zxing.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
