package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.parsedValue
import io.specmatic.stub.HttpStubData
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class FreeFormObjectsTest {
    @Test
    fun `when schema is an object with additional properties of type object with no properties defined`() {
        val spec =
            """
---
openapi: 3.0.1
info:
title: API
version: 1
paths:
  /data:
    post:
      summary: API
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                payload:
                  type: object
                  additionalProperties:
                    type: object
      responses:
        200:
          description: API
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val request =
            HttpRequest(
                "POST",
                "/data",
                body = parsedValue(""" { "payload" : {"data" : { "id": 10, "name": "John" } } } """)
            )
        val response = HttpResponse.OK

        val stub: HttpStubData = feature.matchingStub(request, response)
        Assertions.assertThat(stub.requestType.matches(request, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `when schema is an object with additional properties of type object with with no properties defined and additional properties set as true`() {
        val spec =
            """
---
openapi: 3.0.1
info:
title: API
version: 1
paths:
  /data:
    post:
      summary: API
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                payload:
                  type: object
                  additionalProperties:
                    type: object
                    additionalProperties: true
      responses:
        200:
          description: API
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val request =
            HttpRequest(
                "POST",
                "/data",
                body = parsedValue(""" { "payload" : {"data1" : { "id": 10, "name": "John" }, "data2" : { "id": 10, "Age": 20 } } } """)
            )
        val response = HttpResponse.OK

        val stub: HttpStubData = feature.matchingStub(request, response)
        Assertions.assertThat(stub.requestType.matches(request, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @ParameterizedTest
    @MethodSource("freeFormObjectData")
    fun `when schema is defined just as an object with no properties defined`(data: String) {
        val spec =
            """
---
openapi: 3.0.1
info:
title: API
version: 1
paths:
    /data:
        post:
          summary: API
          parameters: []
          requestBody:
            content:
              application/json:
                schema:
                  type: object
                  properties:
                    payload:
                      type: object
          responses:
            200:
              description: API
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val request =
            HttpRequest("POST", "/data", body = parsedValue("""{ "payload" : { "id": 10, "data": $data } }"""))
        val response = HttpResponse.OK

        val stub: HttpStubData = feature.matchingStub(request, response)
        Assertions.assertThat(stub.requestType.matches(request, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    companion object {
        @JvmStatic
        fun freeFormObjectData() = listOf(
            """ "" """,
            """ "some data" """,
            """{ "id": 10, "name": "John"}""",
            """{ "id": 10, "name": "John" , "address": {"street": "abc"} }"""
        )
    }
}
