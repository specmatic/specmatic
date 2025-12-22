import io.specmatic.gradle.extensions.RepoType

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
    releasePublishTasks = listOf(
        "dockerBuildxPublish",
        "publishAllPublicationsToMavenCentralRepository",
        "publishAllPublicationsToSpecmaticPrivateRepository",
        "publishAllPublicationsToSpecmaticReleasesRepository",
    )


    withOSSLibrary(project(":specmatic-core")) {
        githubRelease()

        publishToMavenCentral()
        publishTo("specmaticPrivate", "https://repo.specmatic.io/private", RepoType.PUBLISH_ALL)
        publishTo("specmaticSnapshots", "https://repo.specmatic.io/snapshots", RepoType.PUBLISH_ALL)
        publishTo("specmaticReleases", "https://repo.specmatic.io/releases", RepoType.PUBLISH_ALL)

        publish {
            pom {
                name = "Specmatic"
                description =
                    "Turn your contracts into executable specifications. Contract Driven Development - Collaboratively Design & Independently Deploy MicroServices & MicroFrontends."
                url = "https://specmatic.io"
                licenses {
                    license {
                        name = "MIT"
                        url = "https://github.com/specmatic/specmatic/blob/main/License.md"
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
                    connection = "https://github.com/specmatic/specmatic"
                    url = "https://specmatic.io/"
                }
            }
        }
    }
    withOSSLibrary(project(":junit5-support")) {
        githubRelease()

        publishToMavenCentral()
        publishTo("specmaticPrivate", "https://repo.specmatic.io/private", RepoType.PUBLISH_ALL)
        publishTo("specmaticSnapshots", "https://repo.specmatic.io/snapshots", RepoType.PUBLISH_ALL)
        publishTo("specmaticReleases", "https://repo.specmatic.io/releases", RepoType.PUBLISH_ALL)

        publish {
            pom {
                name = "SpecmaticJUnit5Support"
                description = "Specmatic JUnit 5 Support"
                url = "https://specmatic.io"
                licenses {
                    license {
                        name = "MIT"
                        url = "https://github.com/specmatic/specmatic/blob/main/License.md"
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
                    connection = "https://github.com/specmatic/specmatic"
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

        publishToMavenCentral()
        publishTo("specmaticPrivate", "https://repo.specmatic.io/private", RepoType.PUBLISH_ALL)
        publishTo("specmaticSnapshots", "https://repo.specmatic.io/snapshots", RepoType.PUBLISH_ALL)
        publishTo("specmaticReleases", "https://repo.specmatic.io/releases", RepoType.PUBLISH_ALL)

        dockerBuild {
            imageName = "specmatic"
        }
        publish {
            pom {
                name = "Specmatic Executable"
                description = "Command-line standalone executable jar for Specmatic"
                url = "https://specmatic.io"
                licenses {
                    license {
                        name = "MIT"
                        url = "https://github.com/specmatic/specmatic/blob/main/License.md"
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
                    connection = "https://github.com/specmatic/specmatic"
                    url = "https://specmatic.io/"
                }
            }
        }
    }
    withOSSLibrary(project(":specmatic-mcp")) {
        githubRelease()

        publishToMavenCentral()
        publishTo("specmaticPrivate", "https://repo.specmatic.io/private", RepoType.PUBLISH_ALL)
        publishTo("specmaticSnapshots", "https://repo.specmatic.io/snapshots", RepoType.PUBLISH_ALL)
        publishTo("specmaticReleases", "https://repo.specmatic.io/releases", RepoType.PUBLISH_ALL)

        publish {
            pom {
                name = "Specmatic"
                description =
                    "Turn your contracts into executable specifications. Contract Driven Development - Collaboratively Design & Independently Deploy MicroServices & MicroFrontends."
                url = "https://specmatic.io"
                licenses {
                    license {
                        name = "MIT"
                        url = "https://github.com/specmatic/specmatic/blob/main/License.md"
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
                    connection = "https://github.com/specmatic/specmatic"
                    url = "https://specmatic.io/"
                }
            }
        }
    }
}


subprojects {
    tasks.withType<Test> {
        systemProperty("specmatic.license.utilization.shipDisabled", "true")
    }
}
