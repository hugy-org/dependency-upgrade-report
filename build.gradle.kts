import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm") version "2.3.21" apply false
}

group = "hugy"
version = "0.1.0-SNAPSHOT"

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    dependencies {
        "implementation"("io.github.oshai:kotlin-logging-jvm:7.0.3")
        "testImplementation"(kotlin("test"))
        "testRuntimeOnly"("ch.qos.logback:logback-classic:1.5.18")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
