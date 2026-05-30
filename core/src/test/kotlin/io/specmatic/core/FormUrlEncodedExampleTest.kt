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
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.ServerSocket

internal class FormUrlEncodedExampleTest {
    @Test
    fun `contract tests use inline form-urlencoded examples`() {
        val feature = inlineFeature()

        assertContractTestUsesFormFields(feature)
    }

    @Test
    fun `contract tests use external form-urlencoded examples`() {
        val (feature, unusedExamples) = externalFeatureWithExamples(EXTERNAL_SPEC_FILE)

        assertThat(unusedExamples).isEmpty()

        assertContractTestUsesFormFields(feature)
    }

    @Test
    fun `validate accepts inline form-urlencoded examples`() {
        val feature = inlineFeature()

        assertExampleFormFields(feature)
        feature.validateExamplesOrException()
    }

    @Test
    fun `validate accepts external form-urlencoded examples`() {
        val (feature, unusedExamples) = externalFeatureWithExamples(EXTERNAL_SPEC_FILE)

        assertThat(unusedExamples).isEmpty()
        assertExampleFormFields(feature)
        feature.validateExamplesOrException()
    }

    @Test
    fun `mock uses inline form-urlencoded examples`() {
        val feature = inlineFeature()

        HttpStub(feature, host = "localhost", port = randomFreePort()).use { stub ->
            val response = stub.client.execute(tokenRequest())

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).contains("example-token")
        }
    }

    @Test
    fun `mock uses external form-urlencoded examples`() {
        val feature = OpenApiSpecification.fromFile(EXTERNAL_SPEC_FILE.canonicalPath).toFeature()
        val example = ScenarioStub.readFromFile(EXTERNAL_EXAMPLE_FILE)

        HttpStub(feature, listOf(example), host = "localhost", port = randomFreePort()).use { stub ->
            val response = stub.client.execute(tokenRequest())

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).contains("example-token")
        }
    }

    @Test
    fun `form-urlencoded external example validation reports missing required form fields as missing properties`() {
        val feature = OpenApiSpecification
            .fromFile(EXTERNAL_MISSING_REQUIRED_FORM_FIELD_SPEC_FILE.canonicalPath)
            .toFeature()
            .loadExternalisedExamples()

        val exception = assertThrows<ContractException> {
            feature.validateExamplesOrException()
        }

        val report = exception.report()
        assertThat(report).contains(MISSING_REQUIRED_FORM_FIELD_EXAMPLE_FILE.name)
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

    private fun inlineFeature(): Feature {
        return OpenApiSpecification.fromFile(INLINE_SPEC_FILE.canonicalPath).toFeature()
    }

    private fun externalFeatureWithExamples(specFile: File) =
        OpenApiSpecification
            .fromFile(specFile.canonicalPath)
            .toFeature()
            .loadExternalisedExamplesAndListUnloadableExamples()

    private fun assertExampleFormFields(feature: Feature) {
        val exampleRow = feature.scenarios.single().examples.single().rows.single()

        assertThat(exampleRow.requestExample?.formFields).isEqualTo(VALID_FORM_FIELDS)
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

    private fun randomFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private companion object {
        const val FORM_URLENCODED = "application/x-www-form-urlencoded"
        const val FORM_URLENCODED_RESOURCE_ROOT = "src/test/resources/openapi/form_urlencoded"
        val INLINE_SPEC_FILE = File("$FORM_URLENCODED_RESOURCE_ROOT/inline_token.yaml")
        val EXTERNAL_SPEC_FILE = File("$FORM_URLENCODED_RESOURCE_ROOT/external_token.yaml")
        val EXTERNAL_EXAMPLE_FILE = File("$FORM_URLENCODED_RESOURCE_ROOT/external_token_examples/valid-token-example.json")
        val EXTERNAL_MISSING_REQUIRED_FORM_FIELD_SPEC_FILE =
            File("$FORM_URLENCODED_RESOURCE_ROOT/external_token_missing_client_id.yaml")
        val MISSING_REQUIRED_FORM_FIELD_EXAMPLE_FILE =
            File("$FORM_URLENCODED_RESOURCE_ROOT/external_token_missing_client_id_examples/missing-client-id-example.json")
        val VALID_FORM_FIELDS = mapOf(
            "client_id" to "client-123",
            "client_secret" to "secret-456",
            "grant_type" to "client_credentials",
            "scope" to "dummy/.default"
        )
    }
}
