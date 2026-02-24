package io.specmatic.core.config.v3

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

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

    @Nested
    inner class ModificationMethods {
        @Test
        fun `copyResiliencyTestsConfig should create test config when missing`() {
            val updated = v3Config("version: 3").copyResiliencyTestsConfig(onlyPositive = false)
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
}
