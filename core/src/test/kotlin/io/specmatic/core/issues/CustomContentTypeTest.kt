package io.specmatic.core.issues

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.value.Value
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CustomContentTypeTest {
    @Test
    fun `stub generated response should use custom content-type from spec not text plain`() {
        val feature = OpenApiSpecification.fromYAML(
            """
openapi: "3.0.3"
info:
  version: 1.0.0
  title: Test API
paths:
  /hello:
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: string
      responses:
        '200':
          description: success
          content:
            application/vnd.company.custom+json:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val scenario = feature.scenarios.first()
        val resolver = io.specmatic.core.Resolver()
        val generatedResponse = scenario.httpResponsePattern.generateResponseWithAll(resolver)
        
        assertThat(generatedResponse.headers[CONTENT_TYPE])
            .isEqualTo("application/vnd.company.custom+json")
    }

    @Test
    fun `stub generated response should fallback to default content-type when spec does not specify`() {
        val responsePattern = io.specmatic.core.HttpResponsePattern(
            headersPattern = io.specmatic.core.HttpHeadersPattern(
                pattern = emptyMap(),
                contentType = null
            ),
            status = 200,
            body = io.specmatic.core.pattern.StringPattern()
        )

        val resolver = io.specmatic.core.Resolver()
        val generatedResponse = responsePattern.generateResponseWithAll(resolver)
        
        assertThat(generatedResponse.headers[CONTENT_TYPE]).isEqualTo("text/plain")
    }
}
