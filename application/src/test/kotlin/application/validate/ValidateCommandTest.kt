package application.validate

import application.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ValidateCommandTest {
    @Test
    fun `should fail when example accept header does not allow response content type`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("api.yaml").apply {
            writeText(
                """
                openapi: 3.0.0
                info:
                  title: Products API
                  version: 1.0.0
                paths:
                  /products:
                    get:
                      parameters:
                        - in: header
                          name: Accept
                          required: true
                          schema:
                            type: string
                      responses:
                        "200":
                          description: OK
                          content:
                            application/json:
                              schema:
                                type: object
                """.trimIndent()
            )
        }

        tempDir.resolve("api_examples").mkdirs()
        tempDir.resolve("api_examples/example.json").writeText(
            """
            {
              "http-request": {
                "method": "GET",
                "path": "/products",
                "headers": {
                  "Accept": "application/xml"
                }
              },
              "http-response": {
                "status": 200,
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }
            """.trimIndent()
        )

        val command = ValidateCommand().apply {
            file = specFile
        }

        val (output, exitCode) = captureStandardOutput {
            command.call()
        }

        assertThat(exitCode).isEqualTo(1)
        assertThat(output).contains("Accept header \"application/xml\" does not allow response Content-Type \"application/json\"")
    }
}
