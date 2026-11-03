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

// Golden values of the JVM build for the JS tests (CompatGolden.kt, see CompatProbe).
val compatGoldenDir = layout.buildDirectory.dir("generated/compatGolden/kotlin")

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
        useEsModules()
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
            kotlin.srcDir("src/compatTest/kotlin")
            resources.srcDir("src/test/resources")
            dependencies { implementation(libs.junit) }
        }
        jsMain {
            kotlin.srcDir("src/main/kotlin")
            kotlin.exclude("de/luwify/stundenplanmd/core/update/**")
            dependencies { implementation(npm("pako", "2.1.0")) }
        }
        jsTest {
            kotlin.srcDir("src/compatTest/kotlin")
            kotlin.srcDir(compatGoldenDir)
            dependencies { implementation(kotlin("test")) }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The unit tests under the name the rest of the build (and habit) uses: the JVM tests and the JS tests, which check
// that the JS build reproduces the JVM build's output (CompatTest).
tasks.register("test") { dependsOn("jvmTest", "jsNodeTest") }

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

val compatGolden = tasks.register<JavaExec>("compatGolden") {
    dependsOn("jvmTestClasses")
    classpath = jvmTestClasspath
    mainClass.set("de.luwify.stundenplanmd.core.CompatGoldenKt")
    val ics = file("src/test/resources/lsf_scrubbed.ics")
    val out = compatGoldenDir.map { it.file("de/luwify/stundenplanmd/core/compat/CompatGolden.kt") }
    inputs.file(ics)
    inputs.files(jvmTestClasspath)
    outputs.file(out)
    args(ics.absolutePath, out.get().asFile.absolutePath)
}
tasks.named("compileTestKotlinJs") { dependsOn(compatGolden) }

plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
    extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
}
