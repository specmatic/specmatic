package application.validate

import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OpenApiValidatorTest {
    private val validator = OpenApiValidator()

    @Test
    fun `should fail validate example when accept header does not allow response content type`(@TempDir tempDir: File) {
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

        val exampleFile = tempDir.resolve("example.json").apply {
            writeText(
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
        }

        val specValidation = validator.validateSpecification(specFile, SpecmaticConfig())
        val feature = (specValidation as SpecValidationResult.ValidationResult).feature

        val exampleValidation = validator.validateExample(feature, exampleFile, SpecmaticConfig())

        assertThat(exampleValidation.result).isInstanceOf(Result.Failure::class.java)
        assertThat(exampleValidation.result.reportString())
            .contains("Accept header \"application/xml\" does not allow response Content-Type \"application/json\"")
    }
}
