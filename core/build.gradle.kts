import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: parser, model, occurrence expansion, change detection. No Android imports.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.lint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// Dev tool, see PlanReport.kt.
tasks.register<JavaExec>("planReport") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("de.luwify.stundenplanmd.core.PlanReportKt")
    workingDir = rootDir
}

// Dev tool, see PlanCompare.kt.
tasks.register<JavaExec>("planCompare") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("de.luwify.stundenplanmd.core.PlanCompareKt")
    workingDir = rootDir
}

// Dev tool, see ShareFile.kt.
tasks.register<JavaExec>("shareFile") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("de.luwify.stundenplanmd.core.ShareFileKt")
    workingDir = rootDir
}
