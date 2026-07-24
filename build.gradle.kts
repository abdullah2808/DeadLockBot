plugins {
    application
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.0.0"
    id ("com.gradleup.shadow") version "9.2.2"
    id("com.google.protobuf") version "0.10.0"

}

application {
    mainClass.set("MainKt")
}
group = "org.deadlockbot"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    gradlePluginPortal()
}
dependencies {
    implementation("dev.kord:kord-core:0.10.0")
    implementation("io.ktor:ktor-client-core:3.0.0")
    implementation("io.ktor:ktor-client-cio:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:2.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Steam / Deadlock Game Coordinator integration (Phase B).
    implementation("in.dragonbra:javasteam:1.8.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("com.google.protobuf:protobuf-java:4.35.1")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.ktor:ktor-client-mock:3.0.0")
    testImplementation("com.h2database:h2:2.3.232")
}

// Generates Java classes from src/main/proto for the Deadlock GC messages
// (the `java` builtin is enabled by default).
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.1"
    }
}

// Kotlin sources reference the generated protobuf Java, so make sure codegen
// runs first.
tasks.named("compileKotlin") {
    dependsOn("generateProto")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}
