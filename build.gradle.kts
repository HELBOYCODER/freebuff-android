plugins {
    kotlin("jvm") version "1.9.24" apply false
    kotlin("android") version "1.9.24" apply false
    id("com.android.application") version "8.2.2" apply false
}

// JVM-only modules: pure Kotlin, buildable + testable without the Android SDK.
// The :app module configures its own Android + Compose plugin graph.
subprojects {
    if (name == "app") return@subprojects
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "failed", "skipped") }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(17)
    }
}
