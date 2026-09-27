pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Google's maven + the SDK are required only for the Android modules, which
        // are gated behind FB_ANDROID=1 (see settings below). Network-restricted
        // dev boxes (no dl.google.com) still build & test the JVM core.
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "freebuff-android"

// --- Locally-verifiable pure-Kotlin core (JVM; buildable & testable without the
//     Android SDK). These carry the vertical-slice LOGIC mirrored from upstream. ---
include(":core:model")
include(":core:protocol")
include(":core:security")
include(":core:workspace")

// --- Android modules: require the Google SDK + AGP (blocked on this dev box).
//     Enabled in CI / normal-network machines via FB_ANDROID=1. Documented in
//     docs/android-runtime.md + engineering-log.json as a capability gate. ---
if (System.getenv("FB_ANDROID") == "1") {
    include(":core:terminal")
    include(":core:git")
    include(":core:agent")
    include(":feature:workspace")
    include(":feature:editor")
    include(":feature:agent")
    include(":feature:terminal")
    include(":app")
}
