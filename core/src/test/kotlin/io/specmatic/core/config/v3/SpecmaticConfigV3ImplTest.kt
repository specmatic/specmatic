package io.specmatic.core.config.v3

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
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

    companion object {
        data class TestCase(val v2: String, val v3: String, val extract: (SpecmaticConfig) -> Any?) {
            fun run(tempDir: File) {
                val v2Config = tempDir.resolve("v2.yaml").apply { writeText(v2) }.toSpecmaticConfig()
                val v3Config = tempDir.resolve("v2.yaml").apply { writeText(v3) }.toSpecmaticConfig()
                val v2Value = extract(v2Config)
                val v3Value = extract(v3Config)
                assertThat(v2Value).usingRecursiveComparison().ignoringFieldsMatchingRegexes(".*specmaticConfig").isEqualTo(v3Value)
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
