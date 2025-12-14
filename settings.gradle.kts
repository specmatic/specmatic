pluginManagement {
    val specmaticGradlePluginVersion = settings.extra["specmaticGradlePluginVersion"] as String
    plugins {
        id("io.specmatic.gradle") version (specmaticGradlePluginVersion)
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        mavenLocal {
            mavenContent {
                snapshotsOnly()
            }

            content {
                includeGroup("io.specmatic.gradle")
            }
        }
    }
}

rootProject.name = "specmatic"

include("specmatic-executable")
include("specmatic-core")
include("junit5-support")
include("specmatic-mcp")

project(":specmatic-executable").projectDir = file("application")
project(":specmatic-core").projectDir = file("core")
