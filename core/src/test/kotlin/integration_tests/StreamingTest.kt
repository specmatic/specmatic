package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StreamingTest {
    @Test
    fun `should stream tests`() {
        val parameters = (1..30).joinToString("") {
"""        - name: param$it
          in: query
          schema:
            type: string
"""
        }

        val specWith30QueryParams = """
openapi: 3.0.0
info:
  title: "Test API"
  version: "1.0.0"
paths:
  /test:
    get:
      parameters:
$parameters
      responses:
        '200':
          description: "A simple test"
          content:
            text/plain:
              schema:
                type: string
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(specWith30QueryParams, "").toFeature().enableGenerativeTesting()

        val streamOfTestsWithOverABillionTests = feature.generateContractTests()
        val first100TestsFromTheStreamingTest = streamOfTestsWithOverABillionTests.take(100)

        assertThat(first100TestsFromTheStreamingTest.toList()).hasSize(100)
    }


}