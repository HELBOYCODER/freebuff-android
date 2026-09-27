pluginManagement {
    repositories {
        // Google's plugin host (dl.google.com / maven.google.com) is egress-blocked on this
        // dev box, so AGP + plugin markers are resolved through public mirrors instead.
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven/")
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven/")
        mavenCentral()
        google()
    }
}

rootProject.name = "freebuff-android"

// Locally-verifiable pure-Kotlin core (JVM; buildable & testable without the Android SDK).
include(":core:model")
include(":core:protocol")
include(":core:security")
include(":core:workspace")

// Android modules: require the SDK + AGP (gated behind FB_ANDROID=1).
// Terminal/Git/feature modules are authored progressively; enable when present.
if (System.getenv("FB_ANDROID") == "1") {
    include(":app")
}
