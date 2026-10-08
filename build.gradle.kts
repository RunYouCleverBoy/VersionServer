plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
    id("io.ktor.plugin") version "3.5.2"
}

group = "com.playground.versionserver"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-auth-jwt")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("org.jetbrains:markdown:0.7.3")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-content-negotiation")
    testImplementation(kotlin("test"))
}

tasks.processResources {
    from("docs") {
        into("doc-content")
    }
}

kotlin {
    jvmToolchain(22)
}

application {
    mainClass.set("com.playground.versionserver.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
