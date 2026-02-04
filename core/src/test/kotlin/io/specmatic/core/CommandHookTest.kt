package io.specmatic.core

import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.config.v2.SpecmaticConfigV2Impl
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.yamlMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class CommandHookTest {
    companion object {
        private val primaryContractString = """
        ---
        openapi: "3.0.1"
        info:
          title: "Random API"
          version: "1"
        paths:
          /:
            get:
              summary: "Random number"
              parameters: []
              responses:
                "200":
                  description: "Random number"
                  content:
                    text/plain:
                      schema:
                        type: "number"
    """.trimIndent()

        private val secondaryContractString = """
        ---
        openapi: "3.0.1"
        info:
          title: "Random API"
          version: "1"
        paths:
          /:
            get:
              summary: "Random number"
              parameters: []
              responses:
                "200":
                  description: "Random number"
                  content:
                    text/plain:
                      schema:
                        type: "number"
    """.trimIndent()

        @TempDir
        @JvmStatic
        lateinit var dir: File

        @JvmStatic
        lateinit var primary: File

        @JvmStatic
        lateinit var secondary: File

        @BeforeAll
        @JvmStatic
        fun setup() {
            primary = dir.resolve("primary.yaml")
            primary.createNewFile()
            primary.writeText(primaryContractString)

            secondary = dir.resolve("secondary.yaml")
            secondary.createNewFile()
            secondary.writeText(secondaryContractString)
        }
    }

    @Test
    fun `command hook when hook exists`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.json")
        val specmaticConfig = SpecmaticConfigV2.loadFrom(SpecmaticConfigV2Impl(
            emptyList(),
            hooks = mapOf(HookName.stub_load_contract.name to "cat ${secondary.canonicalPath}")
        ))
        configFile.writeText(yamlMapper.writeValueAsString(specmaticConfig))
        Configuration.configFilePath = configFile.canonicalPath
        try {
            val contractInFile = CommandHook(HookName.stub_load_contract).readContract(primary.canonicalPath)
            assertThat(contractInFile.trimIndent()).isEqualTo(secondaryContractString)
        } finally {
            System.clearProperty(Flags.CONFIG_FILE_PATH)
        }
    }

    @Test
    fun `command hook when hook does not exist`() {
        val primary = dir.resolve("primary.yaml")
        primary.createNewFile()
        primary.writeText(primaryContractString)

        val contractInFile = CommandHook(HookName.stub_load_contract).readContract(primary.canonicalPath)
        assertThat(contractInFile.trimIndent()).isEqualTo(primaryContractString)
    }
}
