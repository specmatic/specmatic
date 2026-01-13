package io.specmatic.core.config

import io.specmatic.core.SpecmaticConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VersionAwareConfigParserTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `template values parse json object into stub configuration`() {
        val configFile =
            tempDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    contracts: []
                    stub: '{STUB_CONFIG:{"generative": true}}'
                    """.trimIndent()
                )
            }

        val originalValue = System.getProperty("STUB_CONFIG")
        try {
            System.setProperty("STUB_CONFIG", """{"generative": false}""")
            val config: SpecmaticConfig = configFile.toSpecmaticConfig()
            assertThat(config.getStubGenerative()).isFalse()
        } finally {
            restoreProperty("STUB_CONFIG", originalValue)
        }
    }

    @Test
    fun `quoted json in template values stays a string`() {
        val configFile =
            tempDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    contracts: []
                    hooks:
                      after: '{HOOK_VALUE:default}'
                    """.trimIndent()
                )
            }

        val originalValue = System.getProperty("HOOK_VALUE")
        try {
            System.setProperty("HOOK_VALUE", "\"{\\\"generative\\\": true}\"")
            val config: SpecmaticConfig = configFile.toSpecmaticConfig()
            assertThat(SpecmaticConfig.getHooks(config)["after"]).isEqualTo("""{"generative": true}""")
        } finally {
            restoreProperty("HOOK_VALUE", originalValue)
        }
    }

    @Test
    fun `template values parse json array into examples list`() {
        val configFile =
            tempDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    contracts: []
                    examples: '{EXAMPLE_DIRS:["a","b"]}'
                    """.trimIndent()
                )
            }

        val originalValue = System.getProperty("EXAMPLE_DIRS")
        try {
            System.setProperty("EXAMPLE_DIRS", """["examples/one","examples/two"]""")
            val config: SpecmaticConfig = configFile.toSpecmaticConfig()
            assertThat(config.getExamples()).containsExactly("examples/one", "examples/two")
        } finally {
            restoreProperty("EXAMPLE_DIRS", originalValue)
        }
    }

    @Test
    fun `quoted non-json string keeps content without quotes`() {
        val configFile =
            tempDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    contracts: []
                    hooks:
                      after: '{HOOK_VALUE:default}'
                    """.trimIndent()
                )
            }

        val originalValue = System.getProperty("HOOK_VALUE")
        try {
            System.setProperty("HOOK_VALUE", "\"hello world\"")
            val config: SpecmaticConfig = configFile.toSpecmaticConfig()
            assertThat(SpecmaticConfig.getHooks(config)["after"]).isEqualTo("hello world")
        } finally {
            restoreProperty("HOOK_VALUE", originalValue)
        }
    }

    private fun restoreProperty(
        name: String,
        originalValue: String?,
    ) {
        if (originalValue == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, originalValue)
        }
    }
}
