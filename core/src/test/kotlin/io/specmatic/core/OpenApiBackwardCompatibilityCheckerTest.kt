package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiBackwardCompatibilityCheckerTest {
    @Test
    fun `run should remove failure reasons from generated records without hiding mismatch`() {
        val oldSpec = OpenApiSpecification.fromYAML("""
        openapi: 3.0.1
        info:
          title: Orders API
          version: 1.0.0
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: string
              responses:
                '200':
                  description: ok
        """.trimIndent(), "old.yaml").toFeature()

        val newSpec = OpenApiSpecification.fromYAML("""
        openapi: 3.0.1
        info:
          title: Orders API
          version: 1.0.1
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  text/plain:
                    schema:
                      type: string
              responses:
                '200':
                  description: ok
        """.trimIndent(), "new.yaml").toFeature()

        val records = OpenApiBackwardCompatibilityChecker(oldSpec, newSpec).run()
        val failure = records.map { it.compatResult }.filterIsInstance<Result.Failure>().single()

        assertThat(failure.isFluffy()).isFalse()
        assertThat(failure.traverseFailureReason()).isNull()
        assertThat(Results(listOf(failure)).report()).isNotEqualTo(PATH_NOT_RECOGNIZED_ERROR)
        assertThat(failure.reportString()).isEqualToIgnoringWhitespace("""
        In scenario "POST /orders. Response: ok"
        API: POST /orders -> 200
        ${
            toViolationReportString(
                breadCrumb = "REQUEST.PARAMETERS.HEADER.Content-Type",
                details = "This is text/plain in the new specification, but application/json in the old specification",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `identical specs with a required query param should be request-compatible even when a 4xx response is listed first`() {
        val spec = """
        openapi: 3.0.1
        info:
          title: Products API
          version: 1.0.0
        paths:
          /products:
            get:
              parameters:
                - name: type
                  in: query
                  required: true
                  schema:
                    type: string
              responses:
                '400':
                  description: bad request
                '200':
                  description: ok
        """.trimIndent()

        val oldSpec = OpenApiSpecification.fromYAML(spec, "old.yaml").toFeature()
        val newSpec = OpenApiSpecification.fromYAML(spec, "new.yaml").toFeature()

        val records = OpenApiBackwardCompatibilityChecker(oldSpec, newSpec).run()

        assertThat(records.map { it.compatResult }).allMatch { it is Result.Success }
    }
}
