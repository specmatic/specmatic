package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.wsdlContentToFeature
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CtrfSpecificationPathTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `parseContractFileToFeature normalizes filesystem specification path when spec is inside a git repo`() {
        val repoRoot = tempDir.resolve("repo").apply { mkdirs() }
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
    fun `parseContractFileToFeature leaves git specification path untouched`() {
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
    fun `OpenApiSpecification toFeature does not infer filesystem source metadata from local file`() {
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

    @Test
    fun `parseContractFileToFeature normalizes filesystem specification path for wsdl when source is filesystem`() {
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

    @Test
    fun `getCtrfSpecConfig normalizes v3 filesystem spec path relative to git root`() {
        val repoRoot = tempDir.resolve("repo-v3").apply { mkdirs() }
        runGit(repoRoot, "init")
        val specFile =
            repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                parentFile.mkdirs()
                writeText(minimalOpenApi())
            }
        val configFile = repoRoot.resolve("test/resources/specmatic.yaml").apply {
            parentFile.mkdirs()
            writeText("version: 3")
        }
        val originalConfigPath = Configuration.configFilePath

        try {
            Configuration.configFilePath = configFile.canonicalPath
            val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = "../../specs"))
            val config =
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
                                                                specs = listOf(SpecificationDefinition.StringValue("openapi/order_api.yaml")),
                                                            ),
                                                        ),
                                                    ),
                                            ),
                                        ),
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

            assertThat(ctrfSpecConfig.specification).isEqualTo("specs/openapi/order_api.yaml")
            assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.filesystem.name)
            assertThat(ctrfSpecConfig.repository).isNull()
        } finally {
            Configuration.configFilePath = originalConfigPath
        }
    }

    @Test
    fun `getCtrfSpecConfig leaves v3 web specification path untouched`() {
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
            val source = SourceV3.create(web = SourceV3.Web(url = "https://example.com"))
            val config =
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
                                                                specs = listOf(SpecificationDefinition.StringValue("openapi/order_api.yaml")),
                                                            ),
                                                        ),
                                                    ),
                                            ),
                                        ),
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
            assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.web.name)
        } finally {
            Configuration.configFilePath = originalConfigPath
        }
    }

    @Test
    fun `getCtrfSpecConfig normalizes v1 filesystem spec path relative to git root`() {
        val repoRoot = tempDir.resolve("repo-v1").apply { mkdirs() }
        runGit(repoRoot, "init")
        val specFile =
            repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                parentFile.mkdirs()
                writeText(minimalOpenApi())
            }
        val configFile = repoRoot.resolve("test/resources/specmatic.yaml").apply {
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
                                provider = SourceProvider.filesystem,
                                directory = "../../specs",
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

            assertThat(ctrfSpecConfig.specification).isEqualTo("specs/openapi/order_api.yaml")
            assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.filesystem.name)
            assertThat(ctrfSpecConfig.repository).isNull()
        } finally {
            Configuration.configFilePath = originalConfigPath
        }
    }

    private fun runGit(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + args).start()
        val exitCode = process.waitFor()
        assertThat(exitCode).isZero()
    }

    private fun minimalOpenApi(): String =
        """
        openapi: 3.0.0
        info:
          title: Orders
          version: "1.0"
        paths:
          /orders:
            get:
              responses:
                "200":
                  description: OK
        """.trimIndent()

    private fun minimalWsdl(): String =
        """
        <?xml version="1.0"?>
        <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                          xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                          xmlns:qr="http://specmatic.io/SOAPService/"
                          targetNamespace="http://specmatic.io/SOAPService/">
            <wsdl:types>
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                            targetNamespace="http://specmatic.io/SOAPService/">
                    <xsd:element name="SimpleRequest" type="xsd:string"/>
                    <xsd:element name="SimpleResponse" type="xsd:string"/>
                </xsd:schema>
            </wsdl:types>

            <wsdl:message name="simpleInputMessage">
                <wsdl:part name="simpleInputPart" element="qr:SimpleRequest"/>
            </wsdl:message>
            <wsdl:message name="simpleOutputMessage">
                <wsdl:part name="simpleOutputPart" element="qr:SimpleResponse"/>
            </wsdl:message>

            <wsdl:portType name="simplePortType">
                <wsdl:operation name="SimpleOperation">
                    <wsdl:input name="simpleInput" message="qr:simpleInputMessage"/>
                    <wsdl:output name="simpleOutput" message="qr:simpleOutputMessage"/>
                </wsdl:operation>
            </wsdl:portType>

            <wsdl:binding name="simpleBinding" type="qr:simplePortType">
                <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="SimpleOperation">
                    <soap:operation soapAction="http://specmatic.io/SOAPService/SimpleOperation"/>
                    <wsdl:input name="simpleInput">
                        <soap:body use="literal"/>
                    </wsdl:input>
                    <wsdl:output name="simpleOutput">
                        <soap:body use="literal"/>
                    </wsdl:output>
                </wsdl:operation>
            </wsdl:binding>

            <wsdl:service name="simpleService">
                <wsdl:port name="simplePort" binding="qr:simpleBinding">
                    <soap:address location="http://specmatic.io/SOAPService/SimpleSOAP"/>
                </wsdl:port>
            </wsdl:service>
        </wsdl:definitions>
        """.trimIndent()
}
