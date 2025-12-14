package io.specmatic.conversions

import io.specmatic.core.pattern.*
import io.specmatic.core.pattern.JSONObjectPattern
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScalarExamplesFromOpenApiTest {
    @Test
    fun `date example is propagated into DatePattern`() {
        val pattern = patternFromSchema(
            """
type: string
format: date
example: "2024-05-01"
            """.trimIndent()
        )

        assertThat(pattern).isInstanceOf(DatePattern::class.java)
        assertThat((pattern as DatePattern).example).isEqualTo("2024-05-01")
    }

    @Test
    fun `date time example is propagated into DateTimePattern`() {
        val pattern = patternFromSchema(
            """
type: string
format: date-time
example: "2024-05-01T12:34:56Z"
            """.trimIndent()
        )

        assertThat(pattern).isInstanceOf(DateTimePattern::class.java)
        assertThat((pattern as DateTimePattern).example).isEqualTo("2024-05-01T12:34:56Z")
    }

    @Test
    fun `time example is propagated into TimePattern`() {
        val pattern = patternFromSchema(
            """
type: string
format: time
example: "12:34:56Z"
            """.trimIndent()
        )

        assertThat(pattern).isInstanceOf(TimePattern::class.java)
        assertThat((pattern as TimePattern).example).isEqualTo("12:34:56Z")
    }

    @Test
    fun `uuid example is propagated into UUIDPattern`() {
        val pattern = patternFromSchema(
            """
type: string
format: uuid
example: "123e4567-e89b-12d3-a456-426655440000"
            """.trimIndent()
        )

        assertThat(pattern).isInstanceOf(UUIDPattern::class.java)
        assertThat((pattern as UUIDPattern).example).isEqualTo("123e4567-e89b-12d3-a456-426655440000")
    }

    @Test
    fun `binary example is propagated into BinaryPattern`() {
        val pattern = patternFromSchema(
            """
type: string
format: binary
example: "QQ=="
            """.trimIndent()
        )

        assertThat(pattern).isInstanceOf(BinaryPattern::class.java)
        assertThat((pattern as BinaryPattern).example).isEqualTo("[81, 81, 61, 61]")
    }

    @Test
    fun `email example is propagated into EmailPattern`() {
        val pattern = patternFromSchema(
            """
type: string
format: email
example: "user@example.com"
            """.trimIndent()
        )

        assertThat(pattern).isInstanceOf(EmailPattern::class.java)
        assertThat((pattern as EmailPattern).example).isEqualTo("user@example.com")
    }

    @Test
    fun `uri example is propagated into URLPattern`() {
        val pattern = patternFromSchema(
            """
type: string
format: uri
example: "https://specmatic.io/docs"
            """.trimIndent()
        )

        assertThat(pattern).isInstanceOf(URLPattern::class.java)
        assertThat((pattern as URLPattern).example).isEqualTo("https://specmatic.io/docs")
    }

    private fun patternFromSchema(schemaBlock: String): Pattern {
        val spec = """
            |openapi: 3.0.0
            |info:
            |  title: Scalar Example
            |  version: "1.0"
            |paths:
            |  /scalar:
            |    get:
            |      responses:
            |        "200":
            |          description: OK
            |          content:
            |            application/json:
            |              schema:
            |                type: object
            |                properties:
            |                  value:
            |%s
            |                required:
            |                  - value
        """.trimMargin().format(schemaBlock.prependIndent("                    "))

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val scenario = feature.scenarios.single()
        val responseBody = scenario.httpResponsePattern.body as JSONObjectPattern
        return responseBody.pattern.getValue("value")
    }
}
