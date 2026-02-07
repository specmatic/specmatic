package io.specmatic.core.config.v3

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.utilities.yamlMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.io.path.writeText
import io.zenwave360.jsonrefparser.`$RefParser`
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SpecmaticConfigV3Test {
    private fun loadConfig(yaml: String, dereference: Boolean = true): SpecmaticConfigV3 {
        val resolvedValue: JsonNode = if (dereference) {
            val resolvedValue = `$RefParser`(yaml).parse().dereference().getRefs().schema()
            yamlMapper.convertValue(resolvedValue, JsonNode::class.java)
        } else {
            yamlMapper.readTree(yaml)
        }

        return yamlMapper.convertValue<SpecmaticConfigV3>(resolvedValue)
    }

    @Test
    fun `should be able to deserialize an empty config with version as V3`(@TempDir tempDir: Path) {
        val yaml = """
        version: 3
        """.trimIndent()
        val file = tempDir.resolve("specmatic.yaml")
        file.writeText(yaml)
        assertThat(loadSpecmaticConfig(file.pathString)).isInstanceOf(SpecmaticConfigV3Impl::class.java)
    }

    @Test
    fun `should be able to load specmatic config v3 with all of the fields specified`() {
        // TODO: Ensure it contains all the possible fields
        val configFile = File("src/test/resources/specmaticConfigFiles/v3/refed_out.yaml")
        assertDoesNotThrow { configFile.toSpecmaticConfig() }
    }

    @Nested
    inner class Components {
        @Test
        fun `should deserialize settings with no known keys as context dependent settings`() {
            val yaml = """
            version: 3
            components:
                settings:
                    mySettings:
                        delayInMilliseconds: 10000
            """.trimIndent()
            val config = loadConfig(yaml)
            val settings = config.components?.settings?.get("mySettings")
            assertThat(settings).isInstanceOf(ContextDependentSettings::class.java); settings as ContextDependentSettings
            assertThat(settings.rawValue).containsKey("delayInMilliseconds")
        }

        @Test
        fun `should deserialize settings with known keys as concrete`() {
            val yaml = """
            version: 3
            components:
                settings:
                    mySettings:
                        general:
                            prettyPrint: true
                        mock:
                            delayInMilliseconds: 10000
            """.trimIndent()
            val config = loadConfig(yaml)

            val settings = config.components?.settings?.get("mySettings")
            assertThat(settings).isInstanceOf(ConcreteSettings::class.java); settings as ConcreteSettings
            assertThat(settings.general?.prettyPrint).isTrue
            assertThat(settings.mock?.delayInMilliseconds).isEqualTo(10000)
        }

        @ParameterizedTest
        @ValueSource(strings = ["test", "mock", "stateful-mock"])
        fun `should load when type in specType blocks in components is valid`(type: String) {
            val yaml = """
            version: 3
            components:
              runOptions:
                myServiceRunOptions:
                  openapi:
                    type: $type
                    baseUrl: http://localhost:8080
            """.trimIndent()
            assertDoesNotThrow { loadConfig(yaml) }
        }

        @Test
        fun `runOptions must specify type in specType blocks in components`() {
            val yaml = """
            version: 3
            components:
              runOptions:
                myServiceRunOptions:
                  openapi:
                    baseUrl: http://localhost:8080
            """.trimIndent()

            val exception = assertThrows<Throwable> { loadConfig(yaml) }
            assertThat(exceptionCauseMessage(exception)).contains("missing type id property 'type'")
        }

        @ParameterizedTest
        @ValueSource(strings = ["backwardCompatibility", "proxy"])
        fun `should throw when type in specType blocks in components is invalid`(type: String) {
            val yaml = """
            version: 3
            components:
              runOptions:
                myServiceRunOptions:
                  openapi:
                    type: $type
                    baseUrl: http://localhost:8080
            """.trimIndent()

            val exception = assertThrows<Throwable> { loadConfig(yaml) }
            assertThat(exceptionCauseMessage(exception)).contains("known type ids = [mock, stateful-mock, test]")
        }

        @Test
        fun `type stateful-mock is only valid for openapi runOptions should not be loaded for others`() {
            val yaml = """
            version: 3
            components:
              runOptions:
                myServiceRunOptions:
                  asyncapi:
                    type: stateful-mock
            """.trimIndent()

            val exception = assertThrows<Throwable> { loadConfig(yaml) }
            assertThat(exceptionCauseMessage(exception)).contains("known type ids = [mock, test]")
        }

        @Test
        fun `should not allow testExamples and mockExamples to refer to each other`() {
            val yaml = """
            version: 3
            components:
              examples:
                testExamples:
                  - directories:
                    - ./test-examples
                  - ${'$'}ref: "#/components/examples/mockExamples"
                mockExamples:
                  - directories:
                    - ./mock-examples
            """.trimIndent()
            assertThrows<Throwable> { loadConfig(yaml) }
        }
    }

    @Nested
    inner class Source {
        @ParameterizedTest
        @ValueSource(strings = ["filesystem", "git"])
        fun `should load known valid source types`(type: String) {
            val yaml = """
            version: 3
            components:
              sources:
                mySource:
                  $type:
                    ${if (type == "git") "url" else "directory"}: value
            """.trimIndent()
            assertDoesNotThrow { loadConfig(yaml) }
        }

        @Test
        fun `should only one of mutually exclusive git auth options`() {
            val yaml = """
            version: 3
            components:
              sources:
                mySource:
                  git:
                    url: value
                    auth:
                      bearerFile: "bearer.txt"
            """.trimIndent()
            assertDoesNotThrow { loadConfig(yaml) }
        }

        @ParameterizedTest
        @ValueSource(strings = ["bucket", "aws"])
        fun `should not  load known invalid source types`(type: String) {
            val yaml = """
            version: 3
            components:
              sources:
                mySource:
                  $type:
                    key: value
            """.trimIndent()

            val exception = assertThrows<Throwable> { loadConfig(yaml) }
            assertThat(exceptionCauseMessage(exception)).contains("Must specify either 'git' or 'filesystem'")
        }

        @Test
        fun `should disallow both git and filesystem on the same source`() {
            val yaml = """
            version: 3
            components:
              sources:
                mySource:
                  git:
                    url: value
                  filesystem:
                    directory: value
            """.trimIndent()

            val exception = assertThrows<Throwable> { loadConfig(yaml) }
            assertThat(exceptionCauseMessage(exception)).contains("Specify only one of 'git' or 'filesystem'")
        }

        @Test
        fun `should only allow one of mutually exclusive git auth options`() {
            val yaml = """
            version: 3
            components:
              sources:
                mySource:
                  git:
                    url: value
                    auth:
                      bearerFile: "bearer.txt"
                      bearerEnvironmentVariable: "SPECMATIC_BEARER"
                      personalAccessToken: "pat_example_123"
            """.trimIndent()

            val exception = assertThrows<Throwable> { loadConfig(yaml) }
            assertThat(exceptionCauseMessage(exception)).contains("Authentication methods are mutually exclusive")
        }
    }
}
