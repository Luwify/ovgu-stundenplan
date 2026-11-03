import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Parser, model, occurrence expansion, layout math, share codec. No Android imports.
// Kotlin Multiplatform: the JVM target (used by :app) compiles src/main/kotlin exactly as before; the JS target
// (the web app in web/) compiles the same sources against small JS stand-ins for the few java.* classes they use
// (src/jsMain/kotlin/java/...), so web and Android share one implementation of the formats. update/ stays JVM-only.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.lint)
}

kotlin {
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    js {
        outputModuleName.set("stundenplan-core")
        browser {
            testTask { enabled = false }
        }
        nodejs {
            testTask { useMocha { timeout = "60s" } }
        }
        binaries.library()
        generateTypeScriptDefinitions()
        compilerOptions { target.set("es2015") }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies { implementation(libs.junit) }
        }
        jsMain {
            kotlin.srcDir("src/main/kotlin")
            kotlin.exclude("de/luwify/stundenplanmd/core/update/**")
            dependencies { implementation(npm("pako", "2.1.0")) }
        }
        jsTest.dependencies { implementation(kotlin("test")) }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The JVM unit tests under the name the rest of the build (and habit) uses.
tasks.register("test") { dependsOn("jvmTest") }

val jvmTestClasspath = kotlin.jvm().compilations["test"].let { files(it.output.allOutputs, it.runtimeDependencyFiles) }

fun devTool(name: String, main: String) = tasks.register<JavaExec>(name) {
    dependsOn("jvmTestClasses")
    classpath = jvmTestClasspath
    mainClass.set(main)
    workingDir = rootDir
}

devTool("planReport", "de.luwify.stundenplanmd.core.PlanReportKt") // see PlanReport.kt
devTool("planCompare", "de.luwify.stundenplanmd.core.PlanCompareKt") // see PlanCompare.kt
devTool("shareFile", "de.luwify.stundenplanmd.core.ShareFileKt") // see ShareFile.kt
devTool("manifest", "de.luwify.stundenplanmd.core.ManifestToolKt") // release tool (tools/release.sh, tools/withdraw.sh)
devTool("shareRead", "de.luwify.stundenplanmd.core.ShareReadKt") // see ShareRead.kt
