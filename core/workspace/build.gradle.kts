// Kotlin JVM plugin applied by the root build's `subprojects` block.
repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    api(project(":core:security"))
}
