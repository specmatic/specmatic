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
                includeGroupAndSubgroups("io.specmatic")
            }
        }
    }
}

rootProject.name = "specmatic"

include("specmatic-executable")
include("specmatic-core")
include("junit5-support")
include("specmatic-mcp")
if (providers.gradleProperty("enableConformanceTests").orNull == "true") {
    include("conformance-tests")
}

project(":specmatic-executable").projectDir = file("application")
project(":specmatic-core").projectDir = file("core")
