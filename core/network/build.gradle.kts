// Kotlin JVM plugin + JUnit toolchain are applied by the root build's `subprojects` block.
repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    // These types appear in the public API surface (FreebuffClient ctor / streamChat
    // callbacks), so consumers such as :app must see them on their compile classpath.
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
