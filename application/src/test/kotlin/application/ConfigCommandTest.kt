package application

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockkConstructor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ConfigCommandTest {
    @Test
    fun `should write SpecmaticConfig without doc start marker and quotes`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml")
        configFile.writeText(
            """
            sources:
              - provider: git
                repository: https://github.com/znsio/specmatic-order-contracts.git
                test:
                  - io/specmatic/examples/store/openapi/api_order_v3.yaml
              - test:
                  - order.yaml
        """.trimIndent()
        )

        mockkConstructor(ObjectMapper::class)
        var capturedYamlString = ""
        every { anyConstructed<ObjectMapper>().writeValueAsString(any()) } answers {
            val yamlString = callOriginal() as String
            capturedYamlString = yamlString
            yamlString
        }

        val configUpgradeCommand = ConfigCommand.Upgrade()
        configUpgradeCommand.inputFile = configFile

        configUpgradeCommand.call()

        val expectedOutput = """
            version: 2
            contracts:
            - git:
                url: https://github.com/znsio/specmatic-order-contracts.git
              provides:
              - io/specmatic/examples/store/openapi/api_order_v3.yaml
            - provides:
              - order.yaml
            
        """.trimIndent()

        assertEquals(expectedOutput, capturedYamlString)
    }
}