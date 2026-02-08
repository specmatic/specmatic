package io.specmatic.core.config.v3

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.toSpecmaticConfig
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

    companion object {
        data class TestCase(val v2: String, val v3: String, val extract: (SpecmaticConfig) -> Any) {
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
    }
}
