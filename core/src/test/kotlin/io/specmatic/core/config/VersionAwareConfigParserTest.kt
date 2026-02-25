package io.specmatic.core.config

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.utilities.Flags
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.io.path.Path
import java.util.stream.Stream

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
            assertThat(config.getStubGenerative(File("spec.yaml"))).isFalse()
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
            assertThat(SpecmaticConfigV1V2Common.getHooks(config as SpecmaticConfigV1V2Common)["after"]).isEqualTo("""{"generative": true}""")
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
            assertThat(SpecmaticConfigV1V2Common.getHooks(config as SpecmaticConfigV1V2Common)["after"]).isEqualTo("hello world")
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

    @ParameterizedTest(name = "embedded interpolation default preserves value for {0}")
    @MethodSource("embeddedInterpolationDefaultCases")
    fun `embedded interpolation resolves generic defaults including primitive values and arrays`(templateExpression: String, expectedResolvedValue: String) {
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText("""
            version: 2
            contracts: []
            hooks:
              after: 'prefix-$templateExpression-suffix'
            """.trimIndent())
        }

        val config: SpecmaticConfig = configFile.toSpecmaticConfig()
        assertThat(SpecmaticConfigV1V2Common.getHooks(config as SpecmaticConfigV1V2Common)["after"])
            .isEqualTo("prefix-$expectedResolvedValue-suffix")
    }

    @ParameterizedTest(name = "embedded interpolation uses TEST_PROP property for {0}")
    @MethodSource("embeddedInterpolationTestPropertyCases")
    fun `embedded interpolation uses system property instead of default for specmatic base url`(templateExpression: String, propertyValue: String) {
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText("""
            version: 2
            contracts: []
            hooks:
              after: 'prefix-$templateExpression-suffix'
            """.trimIndent())
        }

        val config = Flags.using("TEST_PROP" to propertyValue) { configFile.toSpecmaticConfig() }
        assertThat(SpecmaticConfigV1V2Common.getHooks(config as SpecmaticConfigV1V2Common)["after"])
            .isEqualTo("prefix-$propertyValue-suffix")
    }

    @ParameterizedTest(name = "isConfigTemplate({0}) -> {1}")
    @MethodSource("isConfigTemplateCases")
    fun `isConfigTemplate identifies template expressions`(value: String, expected: Boolean) {
        assertThat(ConfigTemplateUtils.isConfigTemplate(value)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "resolveConfigTemplateValue({0}) -> {1}")
    @MethodSource("resolveConfigTemplateValueCases")
    fun `resolveConfigTemplateValue resolves templates and leaves plain values unchanged`(value: String, expected: String) {
        assertThat(ConfigTemplateUtils.resolveTemplateValue(value)).isEqualTo(expected)
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

    companion object {
        @JvmStatic
        fun isConfigTemplateCases(): Stream<Arguments> = Stream.of(
            Arguments.of("{SYSTEM_PROP:default-value}", true),
            Arguments.of("\${SYSTEM_PROP:default-value}", true),
            Arguments.of("{VAR1|VAR2:default-value}", true),
            Arguments.of("prefix-{SYSTEM_PROP:default}-suffix", true),
            Arguments.of("prefix-{VAR1|VAR2:default}-suffix", true),
            Arguments.of("{SYSTEM_PROP}", false),
            Arguments.of("\${SYSTEM_PROP}", false),
            Arguments.of("{:default}", false),
            Arguments.of("plain-value", false),
        )

        @JvmStatic
        fun resolveConfigTemplateValueCases(): Stream<Arguments> = Stream.of(
            Arguments.of("{SYSTEM_PROP:default-value}", "default-value"),
            Arguments.of("\${SYSTEM_PROP:default-value}", "default-value"),
            Arguments.of("{VAR1|VAR2:default-value}", "default-value"),
            Arguments.of("\${VAR1|VAR2:default-value}", "default-value"),
            Arguments.of("prefix-{SYSTEM_PROP:default}-suffix", "prefix-default-suffix"),
            Arguments.of("prefix-\${SYSTEM_PROP:default}-suffix", "prefix-default-suffix"),
            Arguments.of("prefix-{VAR1|VAR2:default}-suffix", "prefix-default-suffix"),
            Arguments.of("prefix-\${VAR1|VAR2:default}-suffix", "prefix-default-suffix"),
            Arguments.of("prefix-{A:1}-{B:2}-suffix", "prefix-1-2-suffix"),
            Arguments.of("prefix-\${A:1}-\${B:2}-suffix", "prefix-1-2-suffix"),
            Arguments.of("{SYSTEM_PROP}", "{SYSTEM_PROP}"),
            Arguments.of("\${SYSTEM_PROP}", "\${SYSTEM_PROP}"),
            Arguments.of("{:default}", "{:default}"),
            Arguments.of("plain-value", "plain-value"),
        )

        @JvmStatic
        fun embeddedInterpolationDefaultCases(): Stream<Arguments> = Stream.of(
            Arguments.of("{HOOK_VALUE:plain-text}", "plain-text"),
            Arguments.of("\${HOOK_VALUE:plain-text}", "plain-text"),
            Arguments.of("{HOOK_VALUE:true}", "true"),
            Arguments.of("\${HOOK_VALUE:true}", "true"),
            Arguments.of("{HOOK_VALUE:123}", "123"),
            Arguments.of("\${HOOK_VALUE:123}", "123"),
            Arguments.of("{HOOK_ARRAY:[1,true,\"x\"]}", """[1,true,"x"]"""),
            Arguments.of("\${HOOK_ARRAY:[1,true,\"x\"]}", """[1,true,"x"]"""),
        )

        @JvmStatic
        fun embeddedInterpolationTestPropertyCases(): Stream<Arguments> = Stream.of(
            Arguments.of("{TEST_PROP:default-base}", "https://example.test"),
            Arguments.of("\${TEST_PROP:default-base}", "https://example.test"),
            Arguments.of("{TEST_PROP:[0]}", """[1,true,"x"]"""),
            Arguments.of("\${TEST_PROP:[0]}", """[1,true,"x"]"""),
        )
    }
}
