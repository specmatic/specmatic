package io.specmatic.conversions

import io.specmatic.core.Feature
import io.specmatic.core.NoBodyValue
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.MapEntry.entry
import org.junit.jupiter.api.Test

internal class OpenApiInlineExampleLoadingTest {
    @Test
    fun `matching inline request and response examples are loaded into rows and inline examples`() {
        val spec = """
        ---
        openapi: "3.0.1"
        info:
          title: "Product API"
          version: "1"
        paths:
          /products:
            post:
              requestBody:
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        id:
                          type: integer
                    examples:
                      SUCCESSFUL_API_CALL:
                        value:
                          id: 10
              responses:
                200:
                  description: "product"
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          id:
                            type: integer
                      examples:
                        SUCCESSFUL_API_CALL:
                          value:
                            id: 10
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val row = feature.scenarios.single().examples.single().rows.single()
        val namedStub = feature.inlineNamedStubs.single()

        assertThat(row.name).isEqualTo("SUCCESSFUL_API_CALL")
        assertThat(rowValues(row)).containsExactly(entry("(REQUEST-BODY)", """{"id":10}"""))
        assertThat(row.requestExample?.body).isEqualTo(parsedJSONObject("""{"id":10}"""))
        assertThat(row.responseExample?.status).isEqualTo(200)
        assertThat(row.responseExample?.headers).containsEntry("Content-Type", "application/json")
        assertThat(row.responseExample?.body).isEqualTo(parsedJSONObject("""{"id":10}"""))
        assertThat(namedStub.name).isEqualTo("SUCCESSFUL_API_CALL")
        assertThat(namedStub.stub.request).isEqualTo(row.requestExample)
        assertThat(namedStub.stub.response).isEqualTo(row.responseExample)
        assertThat(namedStub.stub.request.body).isEqualTo(parsedJSONObject("""{"id":10}"""))
        assertThat(namedStub.stub.response.body).isEqualTo(parsedJSONObject("""{"id":10}"""))
    }

    @Test
    fun `response example with no corresponding request example for first 2xx response is loaded into rows only`() {
        val spec = """
        ---
        openapi: "3.0.1"
        info:
          title: "Person API"
          version: "1"
        paths:
          /persons:
            get:
              summary: "Get all persons"
              responses:
                200:
                  description: "all persons"
                  content:
                    text/plain:
                      schema:
                        type: "string"
                      examples:
                        SUCCESSFUL_API_CALL:
                          value: "all persons"
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val row = feature.scenarios.single().examples.single().rows.single()

        assertThat(row.name).isEqualTo("SUCCESSFUL_API_CALL")
        assertThat(rowValues(row)).containsExactly(entry("SPECMATIC-TEST-WITH-NO-REQ-EX", ""))
        assertThat(row.requestExample).isNull()
        assertThat(row.responseExample?.status).isEqualTo(200)
        assertThat(row.responseExample?.headers).containsEntry("Content-Type", "text/plain")
        assertThat(row.responseExample?.body?.toStringLiteral()).isEqualTo("all persons")
        assertThat(row.scenarioStub).isNull()
        assertThat(feature.inlineNamedStubs).isEmpty()
    }

    @Test
    fun `response example with no corresponding request example for non first 2xx response is not loaded`() {
        val spec = """
        ---
        openapi: "3.0.1"
        info:
          title: "Person API"
          version: "1"
        paths:
          /persons:
            get:
              summary: "Get all persons"
              responses:
                200:
                  description: "all persons"
                  content:
                    text/plain:
                      schema:
                        type: "string"
                400:
                  description: "bad request"
                  content:
                    text/plain:
                      schema:
                        type: "string"
                      examples:
                        MISSING_REQUEST:
                          value: "missing request"
        """.trimIndent()

        lateinit var feature: Feature
        val (stdout, _) = captureStandardOutput {
            feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains(missingRequestExampleErrorMessageForTest("MISSING_REQUEST"))
        assertNoRowNamed(feature, "MISSING_REQUEST")
        assertThat(feature.inlineNamedStubs).isEmpty()
    }

    @Test
    fun `request example with no corresponding response example is loaded into matching no-body scenario`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Path Item Parameters With No Body Response
              version: 1.0.0
            paths:
              /tasks/{taskId}:
                parameters:
                  - in: path
                    name: taskId
                    required: true
                    schema:
                      type: string
                    examples:
                      COMPLETE:
                        value: task-10
                  - in: query
                    name: dryRun
                    required: true
                    schema:
                      type: boolean
                    examples:
                      COMPLETE:
                        value: false
                  - in: header
                    name: X-Mode
                    required: true
                    schema:
                      type: string
                    examples:
                      COMPLETE:
                        value: batch
                delete:
                  responses:
                    '204':
                      description: Deleted
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val row = feature.scenarios.single().examples.single().rows.single()
        val namedStub = feature.inlineNamedStubs.single()

        assertThat(row.name).isEqualTo("COMPLETE")
        assertThat(rowValues(row)).containsExactly(
            entry("X-Mode", "batch"),
            entry("dryRun", "false"),
            entry("taskId", "task-10")
        )
        assertThat(row.requestExample).isNull()
        assertThat(row.responseExample).isNull()

        assertThat(namedStub.name).isEqualTo("COMPLETE")
        assertThat(namedStub.stub.request.method).isEqualTo("DELETE")
        assertThat(namedStub.stub.request.path).isEqualTo("/tasks/task-10")
        assertThat(namedStub.stub.request.queryParams.asMap()).containsEntry("dryRun", "false")
        assertThat(namedStub.stub.request.headers).containsEntry("X-Mode", "batch")
        assertThat(namedStub.stub.response.status).isEqualTo(204)
        assertThat(namedStub.stub.response.body).isEqualTo(NoBodyValue)
    }

    @Test
    fun `request example with no corresponding response example and no no-body fallback is not loaded`() {
        val spec = """
        ---
        openapi: "3.0.1"
        info:
          title: "Person API"
          version: "1"
        paths:
          /person:
            post:
              summary: "Create person"
              requestBody:
                content:
                  application/json:
                    schema:
                      required:
                      - age
                      properties:
                        age:
                          type: number
                    examples:
                      MISSING_RESPONSE:
                        value:
                          age: 10
              responses:
                200:
                  description: "Created"
                  content:
                    text/plain:
                      schema:
                        type: string
        """.trimIndent()

        lateinit var feature: Feature
        val (stdout, _) = captureStandardOutput {
            feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains(missingResponseExampleErrorMessageForTest("MISSING_RESPONSE"))
        assertNoRowNamed(feature, "MISSING_RESPONSE")
        assertThat(feature.inlineNamedStubs).isEmpty()
    }

    @Test
    fun `405 and 415 inline response examples are not loaded into rows or inline examples`() {
        val methodNotAllowedSpec = inlineUndeclaredRequestVariantSpec(405, "method-not-allowed")
        val unsupportedMediaTypeSpec = inlineUndeclaredRequestVariantSpec(415, "unsupported-media-type")

        val methodNotAllowedFeature = OpenApiSpecification.fromYAML(methodNotAllowedSpec, "").toFeature()
        val unsupportedMediaTypeFeature = OpenApiSpecification.fromYAML(unsupportedMediaTypeSpec, "").toFeature()

        assertNoRowNamed(methodNotAllowedFeature, "method-not-allowed")
        assertThat(methodNotAllowedFeature.inlineNamedStubs).isEmpty()
        assertNoRowNamed(unsupportedMediaTypeFeature, "unsupported-media-type")
        assertThat(unsupportedMediaTypeFeature.inlineNamedStubs).isEmpty()
    }

    private fun rowValues(row: Row): Map<String, String> = row.columnNames.zip(row.values).toMap()

    private fun assertNoRowNamed(feature: Feature, exampleName: String) {
        assertThat(feature.scenarios.flatMap { it.examples }.flatMap { it.rows }.map { it.name })
            .doesNotContain(exampleName)
    }

    private fun inlineUndeclaredRequestVariantSpec(status: Int, exampleName: String) = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required:
                        - data
                      properties:
                        data:
                          type: string
                    examples:
                      $exampleName:
                        value:
                          data: found
              responses:
                "$status":
                  description: undeclared request variant
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
                      examples:
                        $exampleName:
                          value:
                            error: occurred
    """.trimIndent()
}
