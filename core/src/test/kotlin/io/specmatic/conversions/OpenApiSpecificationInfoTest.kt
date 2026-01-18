package io.specmatic.conversions

import io.specmatic.core.pattern.parsedJSONObject
import io.swagger.v3.oas.models.*
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.security.SecurityScheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import io.specmatic.stub.captureStandardOutput
import io.specmatic.toViolationReportString

class OpenApiSpecificationInfoTest {
    @Test
    fun `should generate summary with a single path and operation`() {
        val openApi = OpenAPI().apply {
            openapi = "3.0.0"
            info = Info().apply {
                title = "Test API"
                version = "1.0.0"
                description = "This is a test API"
            }
            paths = Paths().also { paths ->
                paths.addPathItem("/test", PathItem().also { pathItem ->
                    pathItem.get = Operation().also { operation -> operation.operationId = "testOperation" }
                })
            }
            components = Components().also {
                it.schemas = mutableMapOf("testSchema" to Schema<Any>())
            }
        }

        val expected = """
          API Specification Summary: testFilePath
            OpenAPI Version: 3.0.0
            API Paths: 1, API Operations: 1
            Schema components: 1, Security Schemes: none

        """.trimIndent()

        assertThat(openApiSpecificationInfo("testFilePath", openApi)).isEqualTo(expected)
    }

    @Test
    fun `should generate summary with multiple paths and operations`() {
        val openApi = OpenAPI().apply {
            openapi = "3.0.0"
            info = Info().apply {
                title = "Test API"
                version = "1.0.0"
                description = "This is a test API"
            }
            paths = Paths().also { paths ->
                paths.addPathItem("/test1", PathItem().also { pathItem ->
                    pathItem.get = Operation().also { operation -> operation.operationId = "testOperation1" }
                })
                paths.addPathItem("/test2", PathItem().also { pathItem ->
                    pathItem.post = Operation().also { operation -> operation.operationId = "testOperation2" }
                })
            }
            components = Components().also { components ->
                components.schemas = mutableMapOf("testSchema1" to Schema<Any>(), "testSchema2" to Schema<Any>())
                components.securitySchemes =
                    mutableMapOf("testSecurityScheme" to SecurityScheme().also { it.type = SecurityScheme.Type.APIKEY })
            }
        }

        val expected = """
          API Specification Summary: testFilePath
            OpenAPI Version: 3.0.0
            API Paths: 2, API Operations: 2
            Schema components: 2, Security Schemes: [apiKey]

        """.trimIndent()

        assertThat(openApiSpecificationInfo("testFilePath", openApi)).isEqualTo(expected)
    }

    @Test
    fun `should generate summary with no paths and operations`() {
        val openApi = OpenAPI().apply {
            openapi = "3.0.0"
            info = Info().apply {
                title = "Test API"
                version = "1.0.0"
                description = "This is a test API"
            }
            paths = Paths()
            components = Components()
        }

        val expected = """
          API Specification Summary: testFilePath
            OpenAPI Version: 3.0.0
            API Paths: 0, API Operations: 0
            Schema components: null, Security Schemes: none

        """.trimIndent()

        assertThat(openApiSpecificationInfo("testFilePath", openApi)).isEqualTo(expected)
    }

    @Test
    fun `should be able to load dictionary in yaml format`(@TempDir tempDir: File) {
        val apiFile = tempDir.resolve("api.yaml")
        val yamlDictionary = """
        Schema:
            stringKey: stringValue
            numberKey: 123
            booleanKey: true
            nullKey: null
            nested:
                key: value
            array: 
            - value
        """.trimIndent()
        tempDir.resolve("api_dictionary.yaml").writeText(yamlDictionary)
        val dictionary = OpenApiSpecification.loadDictionary(apiFile.canonicalPath, null)

        val expectedSchemaEntry = parsedJSONObject("""{
        "stringKey": "stringValue",
        "numberKey": 123,
        "booleanKey": true,
        "nullKey": null,
        "nested": {
            "key": "value"
        },
        "array": [
            "value"
        ]
        }""".trimIndent())
        val actualEntry = dictionary.getRawValue("Schema")

        assertThat(actualEntry).isEqualTo(expectedSchemaEntry)
    }

    @Test
    fun `validateSecuritySchemeParameterDuplication should log warning when header parameter duplicates security scheme`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: Greeting API with Authentication
              version: 1.0.0
            paths:
              /greet:
                get:
                  summary: Get a default greeting
                  security:
                    - bearerAuth: []
                  parameters:
                    - name: Authorization
                      in: header
                      required: true
                      schema:
                        type: string
                  responses:
                    200:
                      description: Default greeting retrieved successfully
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - greeting
                            properties:
                              greeting:
                                type: string
            components:
              securitySchemes:
                bearerAuth:
                  type: http
                  scheme: bearer
        """.trimIndent()

        val (output, _) = captureStandardOutput { OpenApiSpecification.fromYAML(spec, "").toFeature()  }

        assertThat(output).containsIgnoringWhitespaces(
            toViolationReportString(
                breadCrumb = "paths./greet.get.parameters[0].name",
                details = "The header/query param named \"Authorization\" for security scheme named \"bearerAuth\" was explicitly re-defined as a parameter. The parameter will be ignored, and should be removed.",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        )
    }

    @Test
    fun `validateSecuritySchemeParameterDuplication should log warning when header parameter duplicates security scheme with ref`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: Greeting API with Authentication
              version: 1.0.0
            paths:
              /greet:
                get:
                  summary: Get a default greeting
                  security:
                    - bearerAuth: []
                  parameters:
                    - ${"$"}ref: '#/components/parameters/AuthorizationHeader'
                  responses:
                    200:
                      description: Default greeting retrieved successfully
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - greeting
                            properties:
                              greeting:
                                type: string
            components:
              securitySchemes:
                bearerAuth:
                  type: http
                  scheme: bearer
              parameters:
                AuthorizationHeader:
                  name: Authorization
                  in: header
                  required: true
                  schema:
                    type: string
        """.trimIndent()

        val (output, _) = captureStandardOutput { OpenApiSpecification.fromYAML(spec, "").toFeature()  }
        assertThat(output).containsIgnoringWhitespaces(
            toViolationReportString(
                breadCrumb = "paths./greet.get.parameters[0].name",
                details = "The header/query param named \"Authorization\" for security scheme named \"bearerAuth\" was explicitly re-defined as a parameter. The parameter will be ignored, and should be removed.",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        )
    }
}
