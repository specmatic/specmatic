package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.SpecmaticConfigV3
import io.specmatic.core.config.v3.SpecmaticConfigV3Impl
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.services.TestServiceConfig
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.TestResultRecord.Companion.CONTRACT_TEST_TEST_TYPE
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileSystemSpecificationPathResolutionTest {
    @TempDir
    lateinit var tempDir: File

    @Nested
    inner class CtrfSpecConfigResolution {
        @Nested
        inner class V3Config {
            @Nested
            inner class FileSystemSource {
                @Test
                fun `paths for specs with source as filesystem are resolved relative to the git repo root when the source directory is the specmatic yaml directory`() {
                    val repoRoot = tempDir.resolve("repo-v3").apply { mkdirs() }
                    runGit(repoRoot, "init")
                    val specFile =
                        repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                            parentFile.mkdirs()
                            writeText(minimalOpenApi())
                        }
                    val configFile = repoRoot.resolve("specmatic.yaml").apply {
                        parentFile.mkdirs()
                        writeText("version: 3")
                    }
                    val originalConfigPath = Configuration.configFilePath

                    try {
                        Configuration.configFilePath = configFile.canonicalPath
                        val config =
                            v3ConfigForFilesystemSpec(
                                configFile = configFile,
                                sourceDirectory = ".",
                                configuredSpecPath = "specs/openapi/order_api.yaml",
                            )

                        val ctrfSpecConfig =
                            config.getCtrfSpecConfig(
                                specFile = specFile,
                                testType = CONTRACT_TEST_TEST_TYPE,
                                protocol = "HTTP",
                                specType = SpecType.OPENAPI.value,
                            )

                        assertThat(ctrfSpecConfig.specification).isEqualTo("specs/openapi/order_api.yaml")
                        assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.filesystem.name)
                        assertThat(ctrfSpecConfig.repository).isNull()
                    } finally {
                        Configuration.configFilePath = originalConfigPath
                    }
                }

                @Test
                fun `paths for specs with source as filesystem are resolved relative to the git repo root when the source directory is explicitly set`() {
                    val repoRoot = tempDir.resolve("repo-v3-specs").apply { mkdirs() }
                    runGit(repoRoot, "init")
                    val specFile =
                        repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                            parentFile.mkdirs()
                            writeText(minimalOpenApi())
                        }
                    val configFile = repoRoot.resolve("specmatic.yaml").apply {
                        parentFile.mkdirs()
                        writeText("version: 3")
                    }
                    val originalConfigPath = Configuration.configFilePath

                    try {
                        Configuration.configFilePath = configFile.canonicalPath
                        val config =
                            v3ConfigForFilesystemSpec(
                                configFile = configFile,
                                sourceDirectory = "specs",
                                configuredSpecPath = "openapi/order_api.yaml",
                            )

                        val ctrfSpecConfig =
                            config.getCtrfSpecConfig(
                                specFile = specFile,
                                testType = CONTRACT_TEST_TEST_TYPE,
                                protocol = "HTTP",
                                specType = SpecType.OPENAPI.value,
                            )

                        assertThat(ctrfSpecConfig.specification).isEqualTo("specs/openapi/order_api.yaml")
                        assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.filesystem.name)
                        assertThat(ctrfSpecConfig.repository).isNull()
                    } finally {
                        Configuration.configFilePath = originalConfigPath
                    }
                }
            }

            @Nested
            inner class NonFileSystemSource {
                @Test
                fun `paths for specs with web sources are left unchanged`() {
                    val repoRoot = tempDir.resolve("repo-v3-web").apply { mkdirs() }
                    runGit(repoRoot, "init")
                    val specFile =
                        repoRoot.resolve(".specmatic/web/example.com/openapi/order_api.yaml").apply {
                            parentFile.mkdirs()
                            writeText(minimalOpenApi())
                        }
                    val configFile = repoRoot.resolve("specmatic.yaml").apply {
                        parentFile.mkdirs()
                        writeText("version: 3")
                    }
                    val originalConfigPath = Configuration.configFilePath

                    try {
                        Configuration.configFilePath = configFile.canonicalPath
                        val config =
                            v3ConfigForWebSpec(
                                configFile = configFile,
                                configuredSpecPath = "openapi/order_api.yaml",
                            )

                        val ctrfSpecConfig =
                            config.getCtrfSpecConfig(
                                specFile = specFile,
                                testType = CONTRACT_TEST_TEST_TYPE,
                                protocol = "HTTP",
                                specType = SpecType.OPENAPI.value,
                            )

                        assertThat(ctrfSpecConfig.specification).isEqualTo("openapi/order_api.yaml")
                        assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.web.name)
                    } finally {
                        Configuration.configFilePath = originalConfigPath
                    }
                }

                @Test
                fun `paths for specs with git sources are left unchanged`() {
                    val repoRoot = tempDir.resolve("repo-v3-git").apply { mkdirs() }
                    runGit(repoRoot, "init")
                    val configFile = repoRoot.resolve("specmatic.yaml").apply {
                        parentFile.mkdirs()
                        writeText("version: 3")
                    }
                    val originalConfigPath = Configuration.configFilePath

                    try {
                        Configuration.configFilePath = configFile.canonicalPath
                        val configuredSpecPath = "openapi/order_api.yaml"
                        val specFile =
                            v3GitSource().resolveSpecification(File(configuredSpecPath)).apply {
                                parentFile.mkdirs()
                                writeText(minimalOpenApi())
                            }
                        val config =
                            v3ConfigForGitSpec(
                                configFile = configFile,
                                configuredSpecPath = configuredSpecPath,
                            )

                        val ctrfSpecConfig =
                            config.getCtrfSpecConfig(
                                specFile = specFile,
                                testType = CONTRACT_TEST_TEST_TYPE,
                                protocol = "HTTP",
                                specType = SpecType.OPENAPI.value,
                            )

                        assertThat(ctrfSpecConfig.specification).isEqualTo("openapi/order_api.yaml")
                        assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.git.name)
                        assertThat(ctrfSpecConfig.repository).isEqualTo("https://example.com/contracts.git")
                    } finally {
                        Configuration.configFilePath = originalConfigPath
                    }
                }
            }
        }

        @Nested
        inner class V1AndV2Config {
            @Nested
            inner class FileSystemSource {
                @Test
                fun `paths for specs with source as filesystem are resolved relative to the git repo root when the source directory is explicitly set`() {
                    val repoRoot = tempDir.resolve("repo-v1").apply { mkdirs() }
                    runGit(repoRoot, "init")
                    val specFile =
                        repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                            parentFile.mkdirs()
                            writeText(minimalOpenApi())
                        }
                    val configFile = repoRoot.resolve("specmatic.yaml").apply {
                        parentFile.mkdirs()
                        writeText("version: 2")
                    }
                    val originalConfigPath = Configuration.configFilePath

                    try {
                        Configuration.configFilePath = configFile.canonicalPath
                        val config =
                            v1ConfigForFilesystemSpec(
                                sourceDirectory = "specs",
                                configuredSpecPath = "openapi/order_api.yaml",
                            )

                        val ctrfSpecConfig =
                            config.getCtrfSpecConfig(
                                specFile = specFile,
                                testType = CONTRACT_TEST_TEST_TYPE,
                                protocol = "HTTP",
                                specType = SpecType.OPENAPI.value,
                            )

                        assertThat(ctrfSpecConfig.specification).isEqualTo("specs/openapi/order_api.yaml")
                        assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.filesystem.name)
                        assertThat(ctrfSpecConfig.repository).isNull()
                    } finally {
                        Configuration.configFilePath = originalConfigPath
                    }
                }
            }

            @Nested
            inner class NonFileSystemSource {
                @Test
                fun `paths for specs with git sources are left unchanged in v1 ctrf spec config`() {
                    val repoRoot = tempDir.resolve("repo-v1-git").apply { mkdirs() }
                    runGit(repoRoot, "init")
                    val specFile =
                        repoRoot.resolve("openapi/order_api.yaml").apply {
                            parentFile.mkdirs()
                            writeText(minimalOpenApi())
                        }
                    val configFile = repoRoot.resolve("specmatic.yaml").apply {
                        parentFile.mkdirs()
                        writeText("version: 2")
                    }
                    val originalConfigPath = Configuration.configFilePath

                    try {
                        Configuration.configFilePath = configFile.canonicalPath
                        val config =
                            SpecmaticConfigV1V2Common(
                                sources =
                                    listOf(
                                        Source(
                                            provider = SourceProvider.git,
                                            test = listOf(SpecExecutionConfig.StringValue("openapi/order_api.yaml")),
                                        ),
                                    ),
                            )

                        val ctrfSpecConfig =
                            config.getCtrfSpecConfig(
                                specFile = specFile,
                                testType = CONTRACT_TEST_TEST_TYPE,
                                protocol = "HTTP",
                                specType = SpecType.OPENAPI.value,
                            )

                        assertThat(ctrfSpecConfig.specification).isEqualTo("openapi/order_api.yaml")
                        assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.git.name)
                        assertThat(ctrfSpecConfig.repository).isNull()
                    } finally {
                        Configuration.configFilePath = originalConfigPath
                    }
                }
            }
        }
    }

    @Nested
    inner class SpecPathResolutionInFeature {
        @Nested
        inner class FileSystemSource {
            @Test
            fun `feature spec paths are resolved relative to the git repo root when the source directory is the specmatic yaml directory`() {
                val repoRoot = tempDir.resolve("feature-repo-config-dir").apply { mkdirs() }
                runGit(repoRoot, "init")
                val specFile =
                    repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                        parentFile.mkdirs()
                        writeText(minimalOpenApi())
                    }

                val feature =
                    parseContractFileToFeature(
                        file = specFile,
                        sourceProvider = SourceProvider.filesystem.name,
                        specificationPath = "specs/openapi/order_api.yaml",
                        specmaticConfig = SpecmaticConfig(),
                    )

                assertThat(feature.specification).isEqualTo("specs/openapi/order_api.yaml")
                assertThat(feature.scenarios).isNotEmpty
                assertThat(feature.scenarios.mapNotNull { it.specification }.distinct()).containsExactly("specs/openapi/order_api.yaml")
            }

            @Test
            fun `feature spec paths are resolved relative to the git repo root when the source directory is explicitly set`() {
                val repoRoot = tempDir.resolve("feature-repo-explicit-dir").apply { mkdirs() }
                runGit(repoRoot, "init")
                val specFile =
                    repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                        parentFile.mkdirs()
                        writeText(minimalOpenApi())
                    }

                val feature =
                    parseContractFileToFeature(
                        file = specFile,
                        sourceProvider = SourceProvider.filesystem.name,
                        specificationPath = "openapi/order_api.yaml",
                        specmaticConfig = SpecmaticConfig(),
                    )

                assertThat(feature.specification).isEqualTo("specs/openapi/order_api.yaml")
                assertThat(feature.scenarios).isNotEmpty
                assertThat(feature.scenarios.mapNotNull { it.specification }.distinct()).containsExactly("specs/openapi/order_api.yaml")
            }

            @Test
            fun `feature spec paths are resolved relative to the git repo root for WSDL specs with filesystem sources`() {
                val repoRoot = tempDir.resolve("repo-wsdl").apply { mkdirs() }
                runGit(repoRoot, "init")
                val wsdlFile =
                    repoRoot.resolve("specs/wsdls/hello.wsdl").apply {
                        parentFile.mkdirs()
                        writeText(minimalWsdl())
                    }

                val feature =
                    parseContractFileToFeature(
                        file = wsdlFile,
                        sourceProvider = SourceProvider.filesystem.name,
                        specificationPath = "wsdls/hello.wsdl",
                        specmaticConfig = SpecmaticConfig(),
                    )

                assertThat(feature.specification).isEqualTo("specs/wsdls/hello.wsdl")
                assertThat(feature.scenarios).isNotEmpty
                assertThat(feature.scenarios.mapNotNull { it.specification }.distinct()).containsExactly("specs/wsdls/hello.wsdl")
            }
        }

        @Nested
        inner class NonFileSystemSource {
            @Test
            fun `paths for specs whose source is not filesystem are left unchanged`() {
                val repoRoot = tempDir.resolve("repo-git-source").apply { mkdirs() }
                runGit(repoRoot, "init")
                val specFile =
                    repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                        parentFile.mkdirs()
                        writeText(minimalOpenApi())
                    }

                val feature =
                    parseContractFileToFeature(
                        file = specFile,
                        sourceProvider = SourceProvider.git.name,
                        specificationPath = "openapi/order_api.yaml",
                        specmaticConfig = SpecmaticConfig(),
                    )

                assertThat(feature.specification).isEqualTo("openapi/order_api.yaml")
                assertThat(feature.scenarios).isNotEmpty
                assertThat(feature.scenarios.mapNotNull { it.specification }.distinct()).containsExactly("openapi/order_api.yaml")
            }

            @Test
            fun `spec path is not set for specs that do not have a filesystem source even when the spec file is on the filesystem`() {
                val repoRoot = tempDir.resolve("repo-openapi-direct").apply { mkdirs() }
                runGit(repoRoot, "init")
                val specFile =
                    repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                        parentFile.mkdirs()
                        writeText(minimalOpenApi())
                    }

                val feature = OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()

                assertThat(feature.specification).isNull()
                assertThat(feature.scenarios).isNotEmpty
                assertThat(feature.scenarios.mapNotNull { it.specification }.distinct()).isEmpty()
            }
        }
    }

    private fun runGit(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + args).start()
        val exitCode = process.waitFor()
        assertThat(exitCode).isZero()
    }

    private fun v3ConfigForFilesystemSpec(
        configFile: File,
        sourceDirectory: String,
        configuredSpecPath: String,
    ): SpecmaticConfigV3Impl {
        val filesystemSource = SourceV3.create(filesystem = SourceV3.FileSystem(directory = sourceDirectory))
        return v3Config(configFile, filesystemSource, configuredSpecPath)
    }

    private fun v3ConfigForWebSpec(
        configFile: File,
        configuredSpecPath: String,
    ): SpecmaticConfigV3Impl {
        val webSource = SourceV3.create(web = SourceV3.Web(url = "https://example.com"))
        return v3Config(configFile, webSource, configuredSpecPath)
    }

    private fun v3ConfigForGitSpec(
        configFile: File,
        configuredSpecPath: String,
    ): SpecmaticConfigV3Impl {
        return v3Config(configFile, v3GitSource(), configuredSpecPath)
    }

    private fun v3GitSource(): SourceV3 =
        SourceV3.create(git = SourceV3.Git(url = "https://example.com/contracts.git"))

    private fun v3Config(
        configFile: File,
        source: SourceV3,
        configuredSpecPath: String,
    ): SpecmaticConfigV3Impl =
        SpecmaticConfigV3Impl(
            file = configFile,
            specmaticConfig =
                SpecmaticConfigV3(
                    version = SpecmaticConfigVersion.VERSION_3,
                    systemUnderTest =
                        TestServiceConfig(
                            service =
                                RefOrValue.Value(
                                    CommonServiceConfig<TestRunOptions, TestSettings>(
                                        definitions =
                                            listOf(
                                                Definition(
                                                    Definition.Value(
                                                        source = RefOrValue.Value(source),
                                                        specs = listOf(SpecificationDefinition.StringValue(configuredSpecPath)),
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                ),
        )

    private fun v1ConfigForFilesystemSpec(
        sourceDirectory: String,
        configuredSpecPath: String,
    ): SpecmaticConfigV1V2Common =
        SpecmaticConfigV1V2Common(
            sources =
                listOf(
                    Source(
                        provider = SourceProvider.filesystem,
                        directory = sourceDirectory,
                        test = listOf(SpecExecutionConfig.StringValue(configuredSpecPath)),
                    ),
                ),
        )

    private fun minimalOpenApi(): String =
        """
        openapi: 3.0.0
        info:
          title: Test API
          version: 1.0.0
        paths:
          /orders:
            get:
              responses:
                '200':
                  description: OK
        """.trimIndent()

    private fun minimalWsdl(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://schemas.xmlsoap.org/wsdl/"
                     xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                     xmlns:tns="http://example.com/hello"
                     xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                     targetNamespace="http://example.com/hello"
                     name="HelloService">
          <types>
            <xsd:schema targetNamespace="http://example.com/hello">
              <xsd:element name="sayHello">
                <xsd:complexType>
                  <xsd:sequence>
                    <xsd:element name="name" type="xsd:string"/>
                  </xsd:sequence>
                </xsd:complexType>
              </xsd:element>
              <xsd:element name="sayHelloResponse">
                <xsd:complexType>
                  <xsd:sequence>
                    <xsd:element name="greeting" type="xsd:string"/>
                  </xsd:sequence>
                </xsd:complexType>
              </xsd:element>
            </xsd:schema>
          </types>

          <message name="sayHelloRequest">
            <part name="parameters" element="tns:sayHello"/>
          </message>
          <message name="sayHelloResponse">
            <part name="parameters" element="tns:sayHelloResponse"/>
          </message>

          <portType name="HelloPortType">
            <operation name="sayHello">
              <input message="tns:sayHelloRequest"/>
              <output message="tns:sayHelloResponse"/>
            </operation>
          </portType>

          <binding name="HelloBinding" type="tns:HelloPortType">
            <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
            <operation name="sayHello">
              <soap:operation soapAction="sayHello"/>
              <input>
                <soap:body use="literal"/>
              </input>
              <output>
                <soap:body use="literal"/>
              </output>
            </operation>
          </binding>

          <service name="HelloService">
            <port name="HelloPort" binding="tns:HelloBinding">
              <soap:address location="http://example.com/hello"/>
            </port>
          </service>
        </definitions>
        """.trimIndent()
}
