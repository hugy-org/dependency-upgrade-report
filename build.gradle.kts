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
        "testImplementation"(kotlin("test"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
