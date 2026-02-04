package io.specmatic.core.config

import io.specmatic.core.SpecmaticConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.io.path.Path

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
            assertThat(config.getHooks()["after"]).isEqualTo("""{"generative": true}""")
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
            assertThat(config.getHooks()["after"]).isEqualTo("hello world")
        } finally {
            restoreProperty("HOOK_VALUE", originalValue)
        }
    }

    @Test
    fun `interpolation works in the middle of the specified value`() {
        val configFile =
            tempDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    contracts: []
                    license_path: 'start-{SOME_VAR:default}-end'
                    """.trimIndent()
                )
            }

        val originalValue = System.getProperty("SOME_VAR")
        try {
            System.setProperty("SOME_VAR", "middle")
            val config: SpecmaticConfig = configFile.toSpecmaticConfig()
            assertThat(config.getLicensePath()).isEqualTo(Path("start-middle-end"))
        } finally {
            restoreProperty("SOME_VAR", originalValue)
        }
    }

    @Test
    fun `OR operator on template falls back to second variable, if first variable is missing`() {
        val configFile =
            tempDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    contracts: []
                    license_path: 'foo-{VAR1|VAR2:default-path}-bar'
                    """.trimIndent()
                )
            }

        val originalValueVar1 = System.getProperty("VAR1")
        val originalValueVar2 = System.getProperty("VAR2")
        try {
            System.clearProperty("VAR1")
            System.setProperty("VAR2", "second-var")
            val config: SpecmaticConfig = configFile.toSpecmaticConfig()
            assertThat(config.getLicensePath()).isEqualTo(Path("foo-second-var-bar"))
        } finally {
            restoreProperty("VAR1", originalValueVar1)
            restoreProperty("VAR2", originalValueVar2)
        }
    }

    @Test
    fun `OR operator on template does not fallback if first variable is defined`() {
        val configFile =
            tempDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    contracts: []
                    license_path: 'foo-{VAR1|VAR2:default-path}-bar'
                    """.trimIndent()
                )
            }

        val originalValueVar1 = System.getProperty("VAR1")
        val originalValueVar2 = System.getProperty("VAR2")
        try {
            System.setProperty("VAR1", "first-path")
            System.setProperty("VAR2", "actual-path")
            val config: SpecmaticConfig = configFile.toSpecmaticConfig()
            assertThat(config.getLicensePath()).isEqualTo(Path("foo-first-path-bar"))
        } finally {
            restoreProperty("VAR1", originalValueVar1)
            restoreProperty("VAR2", originalValueVar2)
        }
    }

    @Test
    fun `OR operator on template falls back to default if both variables are missing`() {
        val configFile =
            tempDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    contracts: []
                    license_path: 'foo-{VAR1|VAR2:default-path}-bar'
                    """.trimIndent()
                )
            }

        val originalValueVar1 = System.getProperty("VAR1")
        val originalValueVar2 = System.getProperty("VAR2")
        try {
            System.clearProperty("VAR1")
            System.clearProperty("VAR2")
            val config: SpecmaticConfig = configFile.toSpecmaticConfig()
            assertThat(config.getLicensePath()).isEqualTo(Path("foo-default-path-bar"))
        } finally {
            restoreProperty("VAR1", originalValueVar1)
            restoreProperty("VAR2", originalValueVar2)
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
