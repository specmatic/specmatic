package io.specmatic.core

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.url
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.ServerSocket

internal class FormUrlEncodedExampleTest {
    @Test
    fun `contract tests use inline form-urlencoded examples`() {
        val feature = OpenApiSpecification.fromYAML(inlineTokenSpec(), "").toFeature()

        assertContractTestUsesFormFields(feature)
    }

    @Test
    fun `contract tests use external form-urlencoded examples`(@TempDir tempDir: File) {
        val fixture = formUrlEncodedFixture(tempDir)
        val (feature, unusedExamples) = OpenApiSpecification
            .fromFile(fixture.specFile.canonicalPath)
            .toFeature()
            .loadExternalisedExamplesAndListUnloadableExamples()

        assertThat(unusedExamples).isEmpty()

        val exampleRow = feature.scenarios.single().examples.single().rows.single()
        assertThat(exampleRow.requestExample?.formFields).isEqualTo(VALID_FORM_FIELDS)

        feature.validateExamplesOrException()

        assertContractTestUsesFormFields(feature)
    }

    @Test
    fun `mock uses inline form-urlencoded examples`() {
        val feature = OpenApiSpecification.fromYAML(inlineTokenSpec(), "").toFeature()

        HttpStub(feature, host = "localhost", port = randomFreePort()).use { stub ->
            val response = stub.client.execute(tokenRequest())

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).contains("example-token")
        }
    }

    @Test
    fun `mock uses external form-urlencoded examples`(@TempDir tempDir: File) {
        val fixture = formUrlEncodedFixture(tempDir)
        val feature = OpenApiSpecification.fromFile(fixture.specFile.canonicalPath).toFeature()
        val example = ScenarioStub.readFromFile(fixture.validExampleFile)

        HttpStub(feature, listOf(example), host = "localhost", port = randomFreePort()).use { stub ->
            val response = stub.client.execute(tokenRequest())

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).contains("example-token")
        }
    }

    @Test
    fun `form-urlencoded external example validation reports missing required form fields as missing properties`(@TempDir tempDir: File) {
        val fixture = formUrlEncodedFixture(tempDir, formFields = VALID_FORM_FIELDS.minus("client_id"))
        val feature = OpenApiSpecification
            .fromFile(fixture.specFile.canonicalPath)
            .toFeature()
            .loadExternalisedExamples()

        val exception = assertThrows<ContractException> {
            feature.validateExamplesOrException()
        }

        val report = exception.report()
        assertThat(report).contains(fixture.validExampleFile.name)
        assertThat(report).contains("R2001: Missing required property")
        assertThat(report).contains("REQUEST.FORM-FIELDS.client_id")
        assertThat(report).doesNotContain("R2003: Unknown property")
    }

    @Test
    fun `form fields are serialized when generic body is NoBodyValue`() {
        val builder = HttpRequestBuilder().apply {
            url("http://localhost/token")
        }

        HttpRequest(
            method = "POST",
            path = "/token",
            headers = mapOf(CONTENT_TYPE to FORM_URLENCODED),
            formFields = VALID_FORM_FIELDS,
            body = NoBodyValue
        ).buildKTORRequest(builder)

        assertThat(builder.body).isInstanceOf(FormDataContent::class.java)
        val formData = (builder.body as FormDataContent).formData
        VALID_FORM_FIELDS.forEach { (key, value) ->
            assertThat(formData[key]).isEqualTo(value)
        }
    }

    private fun assertContractTestUsesFormFields(feature: Feature) {
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/token")
                assertThat(request.formFields).isEqualTo(VALID_FORM_FIELDS)

                return tokenResponse()
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    private fun tokenRequest(): HttpRequest {
        return HttpRequest(
            method = "POST",
            path = "/token",
            headers = mapOf(CONTENT_TYPE to FORM_URLENCODED),
            formFields = VALID_FORM_FIELDS
        )
    }

    private fun tokenResponse(): HttpResponse {
        return HttpResponse(
            status = 200,
            headers = mapOf(CONTENT_TYPE to "application/json"),
            body = parsedJSONObject("""{"access_token":"example-token"}""")
        )
    }

    private fun formUrlEncodedFixture(
        tempDir: File,
        formFields: Map<String, String> = VALID_FORM_FIELDS
    ): FormUrlEncodedFixture {
        val specFile = tempDir.resolve("token.yaml").also {
            it.writeText(tokenSpec())
        }
        val examplesDir = tempDir.resolve("token_examples").also { it.mkdirs() }
        val exampleFile = examplesDir.resolve("valid-token-example.json").also {
            it.writeText(
                ScenarioStub(
                    request = HttpRequest(
                        method = "POST",
                        path = "/token",
                        headers = mapOf(CONTENT_TYPE to FORM_URLENCODED),
                        formFields = formFields
                    ),
                    response = tokenResponse()
                ).toJSON().toStringLiteral()
            )
        }

        return FormUrlEncodedFixture(specFile, exampleFile)
    }

    private fun inlineTokenSpec(): String {
        return """
            openapi: 3.0.3
            info:
              title: Token API
              version: '1.0'
            paths:
              /token:
                post:
                  requestBody:
                    required: true
                    content:
                      application/x-www-form-urlencoded:
                        examples:
                          valid-token-example:
                            value:
                              client_id: client-123
                              client_secret: secret-456
                              grant_type: client_credentials
                              scope: dummy/.default
                        schema:
                          type: object
                          required:
                            - client_id
                            - client_secret
                            - grant_type
                            - scope
                          properties:
                            client_id:
                              type: string
                            client_secret:
                              type: string
                            grant_type:
                              type: string
                            scope:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          examples:
                            valid-token-example:
                              value:
                                access_token: example-token
                          schema:
                            type: object
                            required:
                              - access_token
                            properties:
                              access_token:
                                type: string
        """.trimIndent()
    }

    private fun tokenSpec(): String {
        return """
            openapi: 3.0.3
            info:
              title: Token API
              version: '1.0'
            paths:
              /token:
                post:
                  requestBody:
                    required: true
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          type: object
                          required:
                            - client_id
                            - client_secret
                            - grant_type
                            - scope
                          properties:
                            client_id:
                              type: string
                            client_secret:
                              type: string
                            grant_type:
                              type: string
                            scope:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - access_token
                            properties:
                              access_token:
                                type: string
        """.trimIndent()
    }

    private data class FormUrlEncodedFixture(
        val specFile: File,
        val validExampleFile: File
    )

    private fun randomFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private companion object {
        const val FORM_URLENCODED = "application/x-www-form-urlencoded"
        val VALID_FORM_FIELDS = mapOf(
            "client_id" to "client-123",
            "client_secret" to "secret-456",
            "grant_type" to "client_credentials",
            "scope" to "dummy/.default"
        )
    }
}
