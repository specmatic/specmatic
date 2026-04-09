package io.specmatic.core.config.v3

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.KeyData
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.core.utilities.FileAssociation
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.security.KeyStore

class SpecmaticConfigV3ImplTest {
    @TempDir
    lateinit var tempDir: File

    @ParameterizedTest
    @MethodSource("loadSourcesTestCases")
    fun `should return similar values between v2 and v3 loadSources`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("externalSpecTypeConfigs")
    fun `should return similar values between v2 and v3 configFor`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("testSettings")
    fun `should return similar values between v2 and v3 test settings`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("stubSettings")
    fun `should return similar values between v2 and v3 stub settings`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("generalSettings")
    fun `should return similar values between v2 and v3 general settings`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("stubData")
    fun `should return similar values between v2 and v3 stub data`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("testData")
    fun `should return similar values between v2 and v3 test data`(testCase: TestCase) = testCase.run(tempDir)

    @Test
    fun `should resolve test certificate from v3 run options`() {
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ./specs
                      specs:
                        - simple.yaml
                runOptions:
                  openapi:
                    baseUrl: https://api.example.com:9443
                    cert:
                      keyStore:
                        file: ./client-cert.jks
                      keyStorePassword: password
            """.trimIndent()
        )

        val keyDataRegistry = config.getTestHttpsConfiguration().toKeyDataRegistry {
            KeyData(emptyKeyStore(), "password")
        }

        assertThat(keyDataRegistry.get("api.example.com", 9443)).isNotNull()
    }

    @Test
    fun `should resolve mTLS setting from v3 mock run options cert`() {
        val config = v3Config(
            """
            version: 3
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                id: order-api
                                path: order.yaml
                    runOptions:
                      openapi:
                        host: localhost
                        port: 9443
                        cert:
                          mtlsEnabled: true
                          keyStore:
                            file: ./server-cert.jks
                          keyStorePassword: password
            """.trimIndent()
        )

        val incomingMtlsRegistry = config.getStubHttpsConfiguration().toIncomingMtlsRegistry()

        assertThat(incomingMtlsRegistry.get("localhost", 9443)).isTrue()
    }

    @Test
    fun `should resolve mTLS setting from referenced v3 mock run options cert`() {
        val config = v3Config(
            """
            version: 3
            components:
              runOptions:
                mtlsMock:
                  openapi:
                    type: mock
                    host: localhost
                    port: 9443
                    cert:
                      mtlsEnabled: true
                      keyStore:
                        file: ./server-cert.jks
                      keyStorePassword: password
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                id: order-api
                                path: order.yaml
                    runOptions:
                      ${'$'}ref: "#/components/runOptions/mtlsMock"
            """.trimIndent()
        )

        val incomingMtlsRegistry = config.getStubHttpsConfiguration().toIncomingMtlsRegistry()

        assertThat(incomingMtlsRegistry.get("localhost", 9443)).isTrue()
    }

    @Test
    fun `should resolve mTLS setting from v3 wsdl mock run options cert`() {
        val config = v3Config(
            """
            version: 3
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                id: payment-wsdl
                                path: payment.wsdl
                    runOptions:
                      wsdl:
                        host: localhost
                        port: 9450
                        cert:
                          mtlsEnabled: true
                          keyStore:
                            file: ./server-cert.jks
                          keyStorePassword: password
            """.trimIndent()
        )

        val incomingMtlsRegistry = config.getStubHttpsConfiguration().toIncomingMtlsRegistry()

        assertThat(incomingMtlsRegistry.get("localhost", 9450)).isTrue()
    }

    @Test
    fun `should reject spec cert override in openapi run options spec`() {
        assertThatThrownBy {
            v3Config(
                """
                version: 3
                systemUnderTest:
                  service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                id: api-spec
                                path: api.yaml
                    runOptions:
                      openapi:
                        specs:
                          - spec:
                              id: api-spec
                              baseUrl: https://localhost:9443
                              cert:
                                keyStore:
                                  file: ./client-cert.jks
                                keyStorePassword: password
                """.trimIndent()
            )
        }.hasMessageContaining("cert")
    }

    @Test
    fun `should reject spec mtlsEnabled override in openapi run options spec`() {
        assertThatThrownBy {
            v3Config(
                """
                version: 3
                dependencies:
                  services:
                    - service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: order-api
                                    path: order.yaml
                        runOptions:
                          openapi:
                            specs:
                              - spec:
                                  id: order-api
                                  mtlsEnabled: true
                """.trimIndent()
            )
        }.hasMessageContaining("mtlsEnabled")
    }

    @Test
    fun `should reject spec cert override in wsdl run options spec`() {
        assertThatThrownBy {
            v3Config(
                """
                version: 3
                dependencies:
                  services:
                    - service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: wsdl-spec
                                    path: service.wsdl
                        runOptions:
                          wsdl:
                            specs:
                              - spec:
                                  id: wsdl-spec
                                  baseUrl: https://localhost:9443
                                  cert:
                                    keyStore:
                                      file: ./server-cert.jks
                                    keyStorePassword: password
                """.trimIndent()
            )
        }.hasMessageContaining("cert")
    }

    @Test
    fun `should reject spec mtlsEnabled override in wsdl run options spec`() {
        assertThatThrownBy {
            v3Config(
                """
                version: 3
                dependencies:
                  services:
                    - service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: wsdl-spec
                                    path: service.wsdl
                        runOptions:
                          wsdl:
                            specs:
                              - spec:
                                  id: wsdl-spec
                                  mtlsEnabled: true
                """.trimIndent()
            )
        }.hasMessageContaining("mtlsEnabled")
    }

    @Test
    fun `should reject incomingMtlsEnabled in v3 config`() {
        assertThatThrownBy {
            v3Config(
                """
                version: 3
                dependencies:
                  services:
                    - service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: order-api
                                    path: order.yaml
                        runOptions:
                          openapi:
                            host: localhost
                            port: 9443
                            cert:
                              incomingMtlsEnabled: true
                              keyStore:
                                file: ./server-cert.jks
                              keyStorePassword: password
                """.trimIndent()
            )
        }.hasMessageContaining("incomingMtlsEnabled")
    }

    @Test
    fun `getStubHooks should return global and file scoped hook associations`() {
        val config = v3Config("""
        version: 3
        dependencies:
          data:
            adapters:
              preSpecmaticRequestProcessor: ./hooks/global_decode.sh
          services:
            - service:
                definitions:
                  - definition:
                      source:
                        filesystem: {}
                      specs:
                        - one.yaml
                        - two.yaml
                data:
                  adapters:
                    postSpecmaticResponseProcessor: ./hooks/service_encode.sh
        """.trimIndent())

        val oneSpecFile = config.getFirstMockSourceMatching { it.specPathInConfig == "one.yaml" }!!.specFile
        val twoSpecFile = config.getFirstMockSourceMatching { it.specPathInConfig == "two.yaml" }!!.specFile
        assertThat(config.getStubHooks()).containsExactlyInAnyOrder(
            FileAssociation.Global(mapOf("preSpecmaticRequestProcessor" to "./hooks/global_decode.sh")),
            FileAssociation.FileScoped(oneSpecFile, mapOf("postSpecmaticResponseProcessor" to "./hooks/service_encode.sh")),
            FileAssociation.FileScoped(twoSpecFile, mapOf("postSpecmaticResponseProcessor" to "./hooks/service_encode.sh")),
        )
    }

    @Nested
    inner class ModificationMethods {
        @Test
        fun `copyResiliencyTestsConfig should create test config when missing`() {
            val updated = v3Config("version: 3").enableResiliencyTests(onlyPositive = false)
            assertThat(updated.getResiliencyTestsEnabled()).isEqualTo(ResiliencyTestSuite.all)
        }

        @Test
        fun `enableResiliencyTests should set all resiliency tests`() {
            val updated = v3Config("version: 3").enableResiliencyTests()
            assertThat(updated.getResiliencyTestsEnabled()).isEqualTo(ResiliencyTestSuite.all)
        }

        @Test
        fun `withTestBaseURL should create test config when missing`() {
            val updated = v3Config("version: 3").withTestBaseURL("http://localhost:9090")
            assertThat(updated.getTestBaseUrl(SpecType.OPENAPI)).isEqualTo("http://localhost:9090")
        }

        @Test
        fun `withTestModes should create test settings when missing`() {
            val updated = v3Config("version: 3").withTestModes(strictMode = true, lenientMode = false)
            assertThat(updated.getTestStrictMode()).isTrue()
            assertThat(updated.getTestLenientMode()).isFalse()
        }

        @Test
        fun `withTestModes should preserve existing values when null is passed`() {
            val updated = v3Config("""
            version: 3
            systemUnderTest:
              service:
                definitions: []
                settings:
                  strictMode: true
                  lenientMode: false
            """.trimIndent()).withTestModes(strictMode = null, lenientMode = true)
            assertThat(updated.getTestStrictMode()).isTrue()
            assertThat(updated.getTestLenientMode()).isTrue()
        }

        @Test
        fun `withTestFilter should update existing openapi test run options`() {
            val updated = v3Config("""
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ./specs
                      specs:
                        - test.yaml
                runOptions:
                  openapi:
                    baseUrl: http://localhost:9000
                    filter: original
            """.trimIndent()).withTestFilter("updated")
            assertThat(updated.getTestFilter()).isEqualTo("updated")
        }

        @Test
        fun `withTestFilter should create and return config when test section is missing`() {
            val original = v3Config("version: 3")
            val updated = original.withTestFilter("updated")
            assertThat(updated.getTestFilter()).isEqualTo("updated")
        }

        @Test
        fun `withTestTimeout should create test settings when missing`() {
            val updated = v3Config("version: 3").withTestTimeout(1234)
            assertThat(updated.getTestTimeoutInMilliseconds()).isEqualTo(1234)
        }

        @Test
        fun `withStubModes should create mock settings when missing`() {
            val updated = v3Config("version: 3").withStubModes(true)
            assertThat(updated.getStubStrictMode(null)).isTrue()
        }

        @Test
        fun `withStubFilter should update existing openapi mock run options`() {
            val updated = v3Config("""
            version: 3
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                path: stub.yaml
                    runOptions:
                      openapi:
                        baseUrl: http://0.0.0.0:9000
                        filter: original
            """.trimIndent()).withStubFilter("updated")
            assertThat(updated.getStubFilter(File("stub.yaml"))).isEqualTo("updated")
        }

        @Test
        fun `withStubFilter should preserve existing filter when null is passed`() {
            val updated = v3Config("""
            version: 3
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                path: stub.yaml
                    runOptions:
                      openapi:
                        baseUrl: http://0.0.0.0:9000
                        filter: original
            """.trimIndent()).withStubFilter(null)
            assertThat(updated.getStubFilter(File("stub.yaml"))).isEqualTo("original")
        }

        @Test
        fun `withGlobalMockDelay should create mock settings when missing`() {
            val updated = v3Config("version: 3").withGlobalMockDelay(250)
            assertThat(updated.getStubDelayInMilliseconds(null)).isEqualTo(250)
        }

        @Test
        fun `withMatchBranch should update test and mock sources`() {
            writeSpec("specs/test.yaml")
            writeSpec("specs/stub.yaml")
            val updated = v3Config("""
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        git:
                          url: https://github.com/org/repo
                          branch: main
                          matchBranch: false
                      specs:
                        - test.yaml
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            git:
                              url: https://github.com/org/repo
                              branch: main
                              matchBranch: false
                          specs:
                            - spec:
                                path: stub.yaml
            """.trimIndent()).withMatchBranch(true)

            val entries = updated.getSpecificationSources().flatMap { it.test + it.mock }
            assertThat(entries).isNotEmpty()
            assertThat(entries.map { it.matchBranch }).containsOnly(true)
        }

        @Test
        fun `plusExamples should append examples to test and mock sources`() {
            writeSpec("specs/test.yaml")
            writeSpec("specs/stub.yaml")
            val updated = v3Config("""
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        git:
                          url: https://github.com/org/repo
                          branch: main
                          matchBranch: false
                      specs:
                        - test.yaml
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            git:
                              url: https://github.com/org/repo
                              branch: main
                              matchBranch: false
                          specs:
                            - spec:
                                path: stub.yaml
            """.trimIndent()).plusExamples(listOf("examples"))

            val entries = updated.getSpecificationSources().flatMap { it.test + it.mock }
            assertThat(entries).isNotEmpty()
            assertThat(entries.map { it.exampleDirs }).allSatisfy {
                assertThat(it).containsExactly("examples")
            }
        }

        @Test
        fun `should fail to load v3 config when service definitions are missing`() {
            assertThatThrownBy {
                v3Config("""
                version: 3
                systemUnderTest:
                  service:
                    runOptions:
                      openapi:
                        baseUrl: http://localhost:9000
                """.trimIndent())
            }.hasMessageContaining("definitions")
        }

        private fun v3Config(yaml: String): SpecmaticConfig {
            return tempDir.resolve("config-${System.nanoTime()}.yaml").apply { writeText(yaml) }.toSpecmaticConfig()
        }

        private fun writeSpec(path: String): File {
            return tempDir.resolve(path).apply {
                parentFile.mkdirs()
                writeText("openapi: 3.0.0\ninfo: { title: test, version: '1.0.0' }\npaths: {}\n")
            }
        }
    }

    @Test
    fun `getStubDictionary should fallback to dependency level dictionary when service dictionary is absent for matched spec`() {
        val config = tempDir.resolve("specmatic.yaml").apply {
            writeText("""
            version: 3
            dependencies:
              services:
              - service:
                  definitions:
                  - definition:
                      source:
                        filesystem: {}
                      specs:
                        - simple.yaml
              data:
                dictionary:
                  path: ./dictionary_global.yaml
            """.trimIndent())
        }.toSpecmaticConfig()

        val specificationSourceEntry = config.getFirstMockSourceMatching { it.specPathInConfig == "simple.yaml" }
        assertThat(specificationSourceEntry).isNotNull
        assertThat(config.getStubDictionary(specificationSourceEntry!!.specFile)).isEqualTo("./dictionary_global.yaml")
    }

    companion object {
        data class TestCase(val v2: String, val v3: String, val extract: (SpecmaticConfig) -> Any?) {
            fun run(tempDir: File) {
                val v2Config = tempDir.resolve("v2.yaml").apply { writeText(v2) }.toSpecmaticConfig()
                val v3Config = tempDir.resolve("v2.yaml").apply { writeText(v3) }.toSpecmaticConfig()
                val v2Value = extract(v2Config)
                val v3Value = extract(v3Config)

                // ALl except GitMonoRepo returns .path instead of .canonicalPath
                assertThat(v2Value)
                    .usingRecursiveComparison()
                    .ignoringFieldsMatchingRegexes(".*specmaticConfig")
                    .isEqualTo(v3Value)
            }

            fun skip(): TestCase? = null
        }

        @JvmStatic
        fun loadSourcesTestCases(): List<TestCase> {
            return listOfNotNull(
                // Common git and fileSystem cases
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      provides:
                        - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - simple.yaml
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - git:
                        url: https://github.com/org/repo
                        branch: feature
                        matchBranch: true
                      provides:
                        - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                git:
                                  url: https://github.com/org/repo
                                  branch: feature
                                  matchBranch: true
                              specs:
                                - simple.yaml
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),

                // baseUrl Cases
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      consumes:
                        - port: 3000
                          basePath: /v1
                          specs:
                            - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    path: simple.yaml
                                    urlPathPrefix: /v1
                          runOptions:
                            openapi:
                              baseUrl: http://0.0.0.0:3000
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      consumes:
                        - port: 3000
                          basePath: /v1
                          specs:
                            - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: MyCustomId
                                    path: simple.yaml
                                    urlPathPrefix: /v1
                          runOptions:
                            openapi:
                              baseUrl: http://localhost:9000
                              specs:
                              - spec:
                                  id: MyCustomId
                                  baseUrl: http://0.0.0.0:3000
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      consumes:
                        - port: 3000
                          basePath: /v1
                          specs:
                            - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: MyCustomId
                                    path: simple.yaml
                                    urlPathPrefix: /v1
                          runOptions:
                            openapi:
                              host: 0.0.0.0
                              port: 3000
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      consumes:
                        - port: 3000
                          basePath: /v1
                          specs:
                            - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: MyCustomId
                                    path: simple.yaml
                                    urlPathPrefix: /v1
                          runOptions:
                            openapi:
                              host: localhost
                              port: 9000
                              specs:
                              - spec:
                                  id: MyCustomId
                                  host: 0.0.0.0
                                  port: 3000
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),
            )
        }

        @JvmStatic
        fun externalSpecTypeConfigs(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      provides:
                        - specType: protobuf
                          config:
                            host: localhost
                            port: 5000
                          specs:
                          - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - simple.yaml
                        runOptions:
                          protobuf:
                            host: localhost
                            port: 5000
                    """.trimIndent(),
                    extract = { it.testConfigFor("simple.yaml", SpecType.PROTOBUF.value)?.resolvedConfig() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      provides:
                        - specType: protobuf
                          config:
                            host: localhost
                            port: 5000
                          specs:
                          - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - simple.yaml
                        runOptions:
                          protobuf:
                            host: localhost
                            port: 5000
                    """.trimIndent(),
                    extract = {
                        val source = it.getFirstTestSourceMatching { source -> source.specPathInConfig == "simple.yaml" } ?: return@TestCase null
                        it.testConfigFor(source.specFile, SpecType.PROTOBUF)?.resolvedConfig()
                    },
                ),
            )
        }

        @JvmStatic
        fun testSettings(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    test:
                      strictMode: true
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    specmatic:
                      settings:
                        test:
                          strictMode: true
                    """.trimIndent(),
                    extract = { it.getTestStrictMode() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    test:
                      strictMode: true
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions: []
                        settings:
                          strictMode: true
                    specmatic:
                      settings:
                        test:
                          strictMode: false
                    """.trimIndent(),
                    extract = { it.getTestStrictMode() },
                ),
            )
        }

        @JvmStatic
        fun stubSettings(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    stub:
                      strictMode: true
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    specmatic:
                      settings:
                        mock:
                          strictMode: true
                    """.trimIndent(),
                    extract = { it.getTestStrictMode() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    stub:
                      strictMode: true
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services: []
                      settings:
                        strictMode: true
                    specmatic:
                      settings:
                        mock:
                          strictMode: false
                    """.trimIndent(),
                    extract = { it.getTestStrictMode() },
                ),
            )
        }

        @JvmStatic
        fun generalSettings(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    fuzzy: true
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    specmatic:
                      settings:
                        general:
                          featureFlags:
                            fuzzyMatcherForPayloads: true
                    """.trimIndent(),
                    extract = { it.getFuzzyMatchingEnabled() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    prettyPrint: false
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    specmatic:
                      settings:
                        general:
                          prettyPrint: false
                    """.trimIndent(),
                    extract = { it.getPrettyPrint() },
                ),
            )
        }

        @JvmStatic
        fun stubData(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    hooks:
                      preSpecmaticRequestProcessor: ./hooks/decode_request_from_consumer.sh
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services: []
                      data:
                        adapters:
                          preSpecmaticRequestProcessor: ./hooks/decode_request_from_consumer.sh
                    """.trimIndent(),
                    extract = { it.getFuzzyMatchingEnabled() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    hooks:
                      preSpecmaticRequestProcessor: ./hooks/decode_request_from_consumer.sh
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services: []
                      data:
                        adapters:
                          ${"$"}ref: "#/components/adapters/myAdapters"
                    components:
                      adapters:
                        myAdapters:
                          preSpecmaticRequestProcessor: ./hooks/decode_request_from_consumer.sh
                    """.trimIndent(),
                    extract = { it.getFuzzyMatchingEnabled() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    stub:
                      dictionary: ./dictionary.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem: {}
                              specs:
                                - simple.yaml
                          data:
                            dictionary:
                              path: ./dictionary_specific.yaml
                      data:
                        dictionary:
                          path: ./dictionary.yaml
                    """.trimIndent(),
                    extract = { it.getStubDictionary(null) },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    stub:
                      dictionary: ./dictionary.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem: {}
                              specs:
                                - simple.yaml
                          data:
                            dictionary:
                              path: ./dictionary.yaml
                      data:
                        dictionary:
                          path: ./dictionary_global.yaml
                    """.trimIndent(),
                    extract = {
                        val source = it.getFirstMockSourceMatching { it.specPathInConfig == "simple.yaml" } ?: return@TestCase it.getStubDictionary(null)
                        it.getStubDictionary(source.specFile)
                    },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    examples:
                    - ./mock-examples
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem: {}
                              specs:
                                - simple.yaml
                      data:
                        examples:
                        - directories:
                          - ./mock-examples
                    """.trimIndent(),
                    extract = {
                        val source = it.getFirstMockSourceMatching { it.specPathInConfig == "simple.yaml" } ?: return@TestCase it.getExamples()
                        it.getStubExampleDirs(source.specFile)
                    },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    examples:
                    - ./mock-examples
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem: {}
                              specs:
                                - simple.yaml
                          data:
                            examples:
                            - directories:
                              - ./mock-examples
                    """.trimIndent(),
                    extract = {
                        val source = it.getFirstMockSourceMatching { it.specPathInConfig == "simple.yaml" } ?: return@TestCase it.getExamples()
                        it.getStubExampleDirs(source.specFile)
                    },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    examples:
                    - ./mock-examples
                    - ./common-examples
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem: {}
                              specs:
                                - simple.yaml
                          data:
                            examples:
                            - directories:
                              - ./mock-examples
                      data:
                        examples:
                        - directories:
                          - ./common-examples
                    """.trimIndent(),
                    extract = {
                        val source = it.getFirstMockSourceMatching { it.specPathInConfig == "simple.yaml" } ?: return@TestCase it.getExamples()
                        it.getStubExampleDirs(source.specFile)
                    },
                ),
            )
        }

        @JvmStatic
        fun testData(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    stub:
                      dictionary: ./dictionary.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions: []
                        data:
                          dictionary:
                            path: ./dictionary.yaml
                    """.trimIndent(),
                    extract = { it.getTestDictionary() },
                ),
            )
        }
    }

    private fun v3Config(yaml: String): SpecmaticConfig {
        return tempDir.resolve("config-${System.nanoTime()}.yaml").apply { writeText(yaml) }.toSpecmaticConfig()
    }

    private fun emptyKeyStore(): KeyStore {
        return KeyStore.getInstance("JKS").apply { load(null, null) }
    }
}
