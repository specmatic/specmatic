plugins {
    id("io.specmatic.gradle")
    id("base")
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

specmatic {
    publishToMavenCentral()
    publishTo("specmaticPrivate", "https://maven.pkg.github.com/znsio/specmatic-private-maven-repo")
    withOSSLibrary(project(":specmatic-core")) {
        githubRelease()
        publish {
            pom {
                name = "Specmatic"
                description =
                    "Turn your contracts into executable specifications. Contract Driven Development - Collaboratively Design & Independently Deploy MicroServices & MicroFrontends."
                url = "https://specmatic.io"
                licenses {
                    license {
                        name = "MIT"
                        url = "https://github.com/znsio/specmatic/blob/main/License.md"
                    }
                }
                developers {
                    developer {
                        id = "specmaticBuilders"
                        name = "Specmatic Builders"
                        email = "info@specmatic.io"
                    }
                }
                scm {
                    connection = "https://github.com/znsio/specmatic"
                    url = "https://specmatic.io/"
                }
            }
        }
    }
    withOSSLibrary(project(":junit5-support")) {
        githubRelease()
        publish {
            pom {
                name = "SpecmaticJUnit5Support"
                description = "Specmatic JUnit 5 Support"
                url = "https://specmatic.io"
                licenses {
                    license {
                        name = "MIT"
                        url = "https://github.com/znsio/specmatic/blob/main/License.md"
                    }
                }
                developers {
                    developer {
                        id = "specmaticBuilders"
                        name = "Specmatic Builders"
                        email = "info@specmatic.io"
                    }
                }
                scm {
                    connection = "https://github.com/znsio/specmatic"
                    url = "https://specmatic.io/"
                }
            }
        }
    }
    withOSSApplicationLibrary(project(":specmatic-executable")) {
        mainClass = "application.SpecmaticApplication"
        githubRelease {
            addFile("unobfuscatedShadowJar", "specmatic.jar")
        }
        dockerBuild(imageName = "specmatic")
        publish {
            pom {
                name = "Specmatic Executable"
                description = "Command-line standalone executable jar for Specmatic"
                url = "https://specmatic.io"
                licenses {
                    license {
                        name = "MIT"
                        url = "https://github.com/znsio/specmatic/blob/main/License.md"
                    }
                }
                developers {
                    developer {
                        id = "specmaticBuilders"
                        name = "Specmatic Builders"
                        email = "info@specmatic.io"
                    }
                }
                scm {
                    connection = "https://github.com/znsio/specmatic"
                    url = "https://specmatic.io/"
                }
            }
        }
    }
}
tasks.getByName("beforeReleaseBuild").dependsOn("check")
tasks.getByName("afterReleaseBuild").dependsOn("publishAllPublicationsToSpecmaticPrivateRepository")
tasks.getByName("afterReleaseBuild").dependsOn("publishAllPublicationsToMavenCentralRepository")
tasks.getByName("afterReleaseBuild").dependsOn("dockerBuildxPublish")
