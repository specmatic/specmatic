package io.specmatic.conversions

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.NoBodyValue
import io.specmatic.core.QueryParameters
import io.specmatic.core.Scenario
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class OpenApiInlineExampleLoadingTest {
    @Test
    fun `matching request and response example names are loaded as rows and inline examples`() {
        val feature = parseFeature(
            """
            openapi: 3.0.0
            info:
              title: Inline examples
              version: 1.0.0
            paths:
              /echo:
                post:
                  requestBody:
                    required: true
                    content:
                      text/plain:
                        schema:
                          type: string
                        examples:
                          MATCHED:
                            value: request-body
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            MATCHED:
                              value: response-body
            """
        )

        val row = feature.rowNamed("MATCHED")
        assertThat(row.valuesByColumn()).containsExactlyEntriesOf(mapOf("(REQUEST-BODY)" to "request-body"))
        assertThat(row.requestExample).isEqualTo(
            HttpRequest("POST", "/echo", headers = mapOf("Content-Type" to "text/plain"), body = StringValue("request-body"))
        )
        assertThat(row.responseExample?.status).isEqualTo(200)
        assertThat(row.responseExample?.body?.toStringLiteral()).isEqualTo("response-body")

        val inlineStub = feature.inlineStubNamed("MATCHED")
        assertThat(inlineStub.request).isEqualTo(
            HttpRequest("POST", "/echo", headers = mapOf("Content-Type" to "text/plain"), body = StringValue("request-body"))
        )
        assertThat(inlineStub.response.status).isEqualTo(200)
        assertThat(inlineStub.response.body.toStringLiteral()).isEqualTo("response-body")
    }

    @Test
    fun `matching parameter and response example names are loaded as rows and inline examples`() {
        val feature = parseFeature(
            """
            openapi: 3.0.0
            info:
              title: Inline examples
              version: 1.0.0
            paths:
              /items/{itemId}:
                get:
                  parameters:
                    - name: itemId
                      in: path
                      required: true
                      schema:
                        type: string
                      examples:
                        PARAM_MATCHED:
                          value: item-123
                    - name: dryRun
                      in: query
                      required: true
                      schema:
                        type: boolean
                      examples:
                        PARAM_MATCHED:
                          value: false
                    - name: X-Mode
                      in: header
                      required: true
                      schema:
                        type: string
                      examples:
                        PARAM_MATCHED:
                          value: batch
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            PARAM_MATCHED:
                              value: item data
            """
        )

        val expectedRequest = HttpRequest(
            "GET",
            "/items/item-123",
            headers = mapOf("X-Mode" to "batch"),
            queryParams = QueryParameters(mapOf("dryRun" to "false"))
        )

        val row = feature.rowNamed("PARAM_MATCHED")
        assertThat(row.valuesByColumn()).containsExactlyEntriesOf(
            mapOf(
                "itemId" to "item-123",
                "dryRun" to "false",
                "X-Mode" to "batch"
            )
        )
        assertThat(row.requestExample).isEqualTo(expectedRequest)
        assertThat(row.responseExample?.status).isEqualTo(200)
        assertThat(row.responseExample?.body?.toStringLiteral()).isEqualTo("item data")

        val inlineStub = feature.inlineStubNamed("PARAM_MATCHED")
        assertThat(inlineStub.request).isEqualTo(expectedRequest)
        assertThat(inlineStub.response.status).isEqualTo(200)
        assertThat(inlineStub.response.body.toStringLiteral()).isEqualTo("item data")
    }

    @Test
    fun `matching json request body and response example names are loaded as rows and inline examples`() {
        val feature = parseFeature(
            """
            openapi: 3.0.0
            info:
              title: Inline examples
              version: 1.0.0
            paths:
              /products:
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          properties:
                            id:
                              type: integer
                        examples:
                          JSON_BODY_MATCHED:
                            value:
                              id: 10
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: integer
                          examples:
                            JSON_BODY_MATCHED:
                              value:
                                id: 10
            """
        )

        val expectedRequest = HttpRequest(
            "POST",
            "/products",
            headers = mapOf("Content-Type" to "application/json"),
            body = parsedJSONObject("""{"id":10}""")
        )

        val row = feature.rowNamed("JSON_BODY_MATCHED")
        assertThat(row.valuesByColumn()).containsExactlyEntriesOf(mapOf("(REQUEST-BODY)" to """{"id":10}"""))
        assertThat(row.requestExample).isEqualTo(expectedRequest)
        assertThat(row.responseExample?.status).isEqualTo(200)
        assertThat(row.responseExample?.body).isEqualTo(parsedJSONObject("""{"id":10}"""))

        val inlineStub = feature.inlineStubNamed("JSON_BODY_MATCHED")
        assertThat(inlineStub.request).isEqualTo(expectedRequest)
        assertThat(inlineStub.response.status).isEqualTo(200)
        assertThat(inlineStub.response.body).isEqualTo(parsedJSONObject("""{"id":10}"""))
    }

    @Test
    fun `response example without request example for first 2xx response is loaded as row only`() {
        val feature = parseFeature(
            """
            openapi: 3.0.0
            info:
              title: Inline examples
              version: 1.0.0
            paths:
              /ping:
                get:
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            RESPONSE_ONLY:
                              value: pong
            """
        )

        val row = feature.rowNamed("RESPONSE_ONLY")
        assertThat(row.valuesByColumn()).containsExactlyEntriesOf(mapOf("SPECMATIC-TEST-WITH-NO-REQ-EX" to ""))
        assertThat(row.requestExample).isNull()
        assertThat(row.responseExample?.status).isEqualTo(200)
        assertThat(row.responseExample?.body?.toStringLiteral()).isEqualTo("pong")

        feature.assertNoInlineStubNamed("RESPONSE_ONLY")
    }

    @Test
    fun `response example without request example for non first 2xx response is not loaded`() {
        val (output, feature) = captureStandardOutput {
            parseFeature(
                """
                openapi: 3.0.0
                info:
                  title: Inline examples
                  version: 1.0.0
                paths:
                  /data:
                    get:
                      responses:
                        '400':
                          description: Bad request
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                RESPONSE_ONLY_400:
                                  value: bad request
                        '201':
                          description: Created
                          content:
                            text/plain:
                              schema:
                                type: string
                        '202':
                          description: Accepted later
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                RESPONSE_ONLY_202:
                                  value: accepted later
                """
            )
        }

        feature.assertNoRowNamed("RESPONSE_ONLY_400")
        feature.assertNoInlineStubNamed("RESPONSE_ONLY_400")
        assertThat(output).contains(missingRequestExampleErrorMessageForTest("RESPONSE_ONLY_400"))
        feature.assertNoRowNamed("RESPONSE_ONLY_202")
        feature.assertNoInlineStubNamed("RESPONSE_ONLY_202")
        assertThat(output).contains(missingRequestExampleErrorMessageForTest("RESPONSE_ONLY_202"))
    }

    @Test
    fun `request example without response example is loaded into no body scenario row and inline example`() {
        val feature = parseFeature(
            """
            openapi: 3.0.0
            info:
              title: Inline examples
              version: 1.0.0
            paths:
              /items/{itemId}:
                delete:
                  parameters:
                    - name: itemId
                      in: path
                      required: true
                      schema:
                        type: string
                      examples:
                        REQUEST_ONLY_NO_BODY:
                          value: 123-to-be-deleted
                  responses:
                    '204':
                      description: No content
            """
        )

        val row = feature.scenarioWithStatus(204).rowNamed("REQUEST_ONLY_NO_BODY")
        assertThat(row.valuesByColumn()).containsExactlyEntriesOf(mapOf("itemId" to "123-to-be-deleted"))
        assertThat(row.requestExample).isNull()
        assertThat(row.responseExample).isNull()

        val inlineStub = feature.inlineStubNamed("REQUEST_ONLY_NO_BODY")
        assertThat(inlineStub.request).isEqualTo(HttpRequest("DELETE", "/items/123-to-be-deleted"))
        assertThat(inlineStub.response.status).isEqualTo(204)
        assertThat(inlineStub.response.body).isEqualTo(NoBodyValue)
    }

    @Test
    fun `request example without response example and without no body fallback is not loaded`() {
        val (output, feature) = captureStandardOutput {
            parseFeature(
                """
                openapi: 3.0.0
                info:
                  title: Inline examples
                  version: 1.0.0
                paths:
                  /echo:
                    post:
                      requestBody:
                        required: true
                        content:
                          text/plain:
                            schema:
                              type: string
                            examples:
                              REQUEST_ONLY:
                                value: request-body
                      responses:
                        '200':
                          description: OK
                          content:
                            text/plain:
                              schema:
                                type: string
                """
            )
        }

        feature.assertNoRowNamed("REQUEST_ONLY")
        feature.assertNoInlineStubNamed("REQUEST_ONLY")
        assertThat(output).contains(missingResponseExampleErrorMessageForTest("REQUEST_ONLY"))
    }

    @Test
    fun `405 and 415 inline response examples are not loaded`() {
        val feature = parseFeature(
            """
            openapi: 3.0.0
            info:
              title: Inline examples
              version: 1.0.0
            paths:
              /method-not-allowed:
                get:
                  parameters:
                    - name: request-id
                      in: header
                      required: true
                      schema:
                        type: string
                      examples:
                        METHOD_NOT_ALLOWED:
                          value: method-not-allowed
                  responses:
                    '405':
                      description: Method not allowed
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            METHOD_NOT_ALLOWED:
                              value: method not allowed
              /unsupported-media:
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          properties:
                            name:
                              type: string
                        examples:
                          UNSUPPORTED_MEDIA:
                            value:
                              name: Jane
                  responses:
                    '415':
                      description: Unsupported media type
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            UNSUPPORTED_MEDIA:
                              value: unsupported media type
            """
        )

        feature.assertNoRowNamed("METHOD_NOT_ALLOWED")
        feature.assertNoInlineStubNamed("METHOD_NOT_ALLOWED")
        feature.assertNoRowNamed("UNSUPPORTED_MEDIA")
        feature.assertNoInlineStubNamed("UNSUPPORTED_MEDIA")
    }

    private fun parseFeature(spec: String): Feature =
        OpenApiSpecification.fromYAML(spec.trimIndent(), "inline-example-loading.yaml").toFeature()

    private fun Feature.rowNamed(name: String): Row = rowsNamed(name).single()

    private fun Scenario.rowNamed(name: String): Row = rowsNamed(name).single()

    private fun Feature.rowsNamed(name: String): List<Row> =
        scenarios.flatMap { scenario -> scenario.rowsNamed(name) }

    private fun Scenario.rowsNamed(name: String): List<Row> =
        examples.flatMap { example -> example.rows }.filter { row -> row.name == name }

    private fun Feature.scenarioWithStatus(status: Int): Scenario =
        scenarios.single { scenario -> scenario.httpResponsePattern.status == status }

    private fun Row.valuesByColumn(): Map<String, String> =
        columnNames.zip(values).toMap()

    private fun Feature.inlineStubNamed(name: String): ScenarioStub =
        inlineNamedStubs.single { inlineStub -> inlineStub.name == name }.stub

    private fun Feature.assertNoRowNamed(name: String) {
        assertThat(rowsNamed(name)).isEmpty()
    }

    private fun Feature.assertNoInlineStubNamed(name: String) {
        assertThat(inlineNamedStubs.map { inlineStub -> inlineStub.name }).doesNotContain(name)
    }
}
