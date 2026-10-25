import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("io.ktor.plugin") version "3.5.2"
}

group = "app.spdvpn"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-compression")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-default-headers")
    implementation("io.ktor:ktor-server-rate-limit")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")

    implementation("org.jsoup:jsoup:1.23.1")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    implementation("ch.qos.logback:logback-classic:1.5.33")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(kotlin("test-junit"))
}

tasks.register<JavaExec>("bootstrapAdmin") {
    group = "administration"
    description = "Create the first SPD administrator from a password on stdin (no default password)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.spdvpn.backend.premium.BootstrapAdminKt")
    standardInput = System.`in`
    args(providers.gradleProperty("adminUsername").orElse("").get(), "--password-stdin")
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

ktor {
    fatJar {
        archiveFileName.set("spd-vpn-backend-all.jar")
    }
}

// Bouncy Castle is used as a library; upstream JAR signatures cannot describe a merged fat JAR.
tasks.withType<Jar>().configureEach {
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
