package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class PassThroughHookTest {
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

        @TempDir
        @JvmStatic
        lateinit var dir: File

        @JvmStatic
        lateinit var primary: File

        @BeforeAll
        @JvmStatic
        fun setup() {
            primary = dir.resolve("primary.yaml")
            primary.createNewFile()
            primary.writeText(primaryContractString)
        }
    }

    @Test
    fun `reads contract`() {
        val contractInFile = PassThroughHook().readContract(primary.canonicalPath)
        assertThat(contractInFile).isEqualTo(primaryContractString)
    }
}
