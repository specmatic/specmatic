package io.specmatic.conversions

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.parsedJSONObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OneOfWithinAnyOfTest {
    val feature: Feature = OpenApiSpecification.fromYAML("""
---
openapi: "3.0.1"
info:
  title: "Person API"
  version: "1.0"
paths:
  /person/{id}:
    get:
      summary: "Get a person's record"
      parameters:
        - name: id
          in: path
          schema:
            type: string
          examples:
            200_OK:
              value: 10
      responses:
        200:
          description: "A person's details"
          content:
            application/json:
              schema:
                ${"$"}ref: "#/components/schemas/PersonRecord"
              examples:
                200_OK:
                  value:
components:
  schemas:
    Id:
      type: object
      properties:
        id:
          type: integer
      required:
        - id
    PersonDetails:
      oneOf:
        - ${"$"}ref: '#/components/schemas/SimpleName'
        - ${"$"}ref: '#/components/schemas/DestructuredName'
    SimpleName:
      type: object
      properties:
        name:
          type: string
      required:
        - name
    DestructuredName:
      type: object
      properties:
        first_name:
          type: string
        last_name:
          type: string
      required:
        - first_name
        - last_name
    PersonRecord:
      allOf:
        - ${"$"}ref: '#/components/schemas/Id'
        - ${"$"}ref: '#/components/schemas/PersonDetails'
        """.trimIndent(), "").toFeature()

    @Test
    fun `matching stub`() {
        val result = feature.scenarios.first().matchesMock(
            HttpRequest(path = "/person/10", method = "GET"),
            HttpResponse.ok(parsedJSONObject("""{"id": 10, "name": "Sherlock Holmes"}""")),
        )

        println(result.reportString())

        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `also matching stub`() {
        val result = feature.scenarios.first().matchesMock(
            HttpRequest(path = "/person/10", method = "GET"),
            HttpResponse.ok(parsedJSONObject("""{"id": 10, "first_name": "Sherlock", "last_name": "Holmes"}"""))
        )

        println(result.reportString())

        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `non matching stub`() {
        val result = feature.scenarios.first().matchesMock(
            HttpRequest(path = "/person/10", method = "GET"),
            HttpResponse.ok(parsedJSONObject("""{"id": 10, "full_name": "Sherlock Holmes"}"""))
        )

        println(result.reportString())

        assertThat(result).isInstanceOf(Failure::class.java)
    }
}
