package `in`.specmatic.conversions

import `in`.specmatic.core.HttpHeadersPattern
import `in`.specmatic.core.pattern.DeferredPattern
import `in`.specmatic.core.pattern.NullPattern
import `in`.specmatic.core.pattern.Pattern
import io.ktor.util.reflect.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.Ignore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

internal class OpenApiSpecificationTest {
    companion object {
        const val OPENAPI_FILE = "openApiTest.yaml"
    }

    @BeforeEach
    fun `setup`() {
        val openAPI = """
openapi: 3.0.0
info:
  title: Sample API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://api.example.com/v1
    description: Optional server description, e.g. Main (production) server
  - url: http://staging-api.example.com
    description: Optional server description, e.g. Internal staging server for testing
paths:
  /hello/{id}:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - in: path
          name: id
          schema:
            type: integer
          required: true
          description: Numeric ID
      responses:
        '200':
          description: Says hello
          content:
            application/json:
              schema:
                type: string
        '404':
          description: Not Found
          content:
            application/json:
              schema:
                type: string
        '400':
          description: Bad Request
          content:
            application/json:
              schema:
                type: string
  /nested/types/without/ref/to/parent:
    get:
      summary: Nested Types
      description: Optional extended description in CommonMark or HTML.
      responses:
        '200':
          description: Returns nested type
          content:
            application/json:
              schema:
                ${"$"}ref: '#/components/schemas/NestedTypeWithoutRef'
  /nested/types/with/ref/to/parent:
    get:
      summary: Nested Types
      description: Optional extended description in CommonMark or HTML.
      responses:
        '200':
          description: Returns nested type
          content:
            application/json:
              schema:
                ${"$"}ref: '#/components/schemas/NestedTypeWithRef'

components:
  schemas:
    NestedTypeWithoutRef:
      description: ''
      type: object
      properties:
        Parent:
          type: object
          properties:
            Child:
              type: object
              properties:
                Parent:
                  type: object
                  properties:
                    Child:
                      type: string
    NestedTypeWithRef:
      description: ''
      type: object
      properties:
        Parent:
          type: object
          properties:
            Child:
              ${"$"}ref: '#/components/schemas/NestedTypeWithRef'
    """.trim()

        val openApiFile = File(OPENAPI_FILE)
        openApiFile.createNewFile()
        openApiFile.writeText(openAPI)

    }

    @AfterEach
    fun `teardown`() {
        File(OPENAPI_FILE).delete()
    }

    @Ignore
    fun `should generate 200 OK scenarioInfos from openAPI`() {
        val openApiSpecification = OpenApiSpecification.fromFile(OPENAPI_FILE)
        val scenarioInfos = openApiSpecification.toScenarioInfos()
        assertThat(scenarioInfos.size).isEqualTo(3)
    }

    @Test
    fun `should not resolve non ref nested types to Deferred Pattern`() {
        val openApiSpecification = OpenApiSpecification.fromFile(OPENAPI_FILE)
        val scenarioInfos = openApiSpecification.toScenarioInfos()
        val nestedTypeWithoutRef = scenarioInfos.first().patterns.getOrDefault("(NestedTypeWithoutRef)", NullPattern)
        assertThat(containsDeferredPattern(nestedTypeWithoutRef)).isFalse
    }

    @Test
    fun `should resolve ref nested types to Deferred Pattern`() {
        val openApiSpecification = OpenApiSpecification.fromFile(OPENAPI_FILE)
        val scenarioInfos = openApiSpecification.toScenarioInfos()
        val nestedTypeWithRef = scenarioInfos.first().patterns["(NestedTypeWithRef)"]
        assertThat(containsDeferredPattern(nestedTypeWithRef!!)).isTrue
    }

    private fun containsDeferredPattern(pattern: Pattern): Boolean {
        if (!pattern.pattern.instanceOf(Map::class)) return false
        val childPattern = (pattern.pattern as Map<String, Pattern?>).values.firstOrNull() ?: return false
        return if (childPattern.instanceOf(DeferredPattern::class)) true
        else containsDeferredPattern(childPattern)
    }

    @Test
    fun `none of the scenarios should expect the Content-Type header`() {
        val openApiSpecification = OpenApiSpecification.fromFile(OPENAPI_FILE)
        val scenarioInfos = openApiSpecification.toScenarioInfos()

        for (scenarioInfo in scenarioInfos) {
            assertNotFoundInHeaders("Content-Type", scenarioInfo.httpRequestPattern.headersPattern)
            assertNotFoundInHeaders("Content-Type", scenarioInfo.httpResponsePattern.headersPattern)
        }
    }

    fun assertNotFoundInHeaders(header: String, headersPattern: HttpHeadersPattern) {
        assertThat(headersPattern.pattern.keys.map { it.lowercase() }).doesNotContain(header.lowercase())
    }
}