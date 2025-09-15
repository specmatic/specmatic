package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.function.Consumer

internal class CsvPatternTest {
    @Test
    fun `it should match a CSV containing numeric values`() {
        assertThat(CsvPattern(NumberPattern()).matches(StringValue("1,2,3"), Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `it should match a CSV with only one value`() {
        assertThat(CsvPattern(NumberPattern()).matches(StringValue("1"), Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `it should return an error when a value in the CSV is of the wrong type`() {
        assertThat(CsvPattern(NumberPattern()).matches(StringValue("1,b"), Resolver())).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `it should generate a CSV containing multiple values`() {
        assertThat(CsvPattern(NumberPattern()).generate(Resolver()).toStringLiteral()).contains(",")

    }

    @Test
    fun `each value in the generated CSV must be of the required type`() {
        val csv = CsvPattern(NumberPattern()).generate(Resolver()).toStringLiteral()
        val parts = csv.split(",")

        assertThat(parts).allSatisfy {
            assertDoesNotThrow { NumberPattern().parse(it, Resolver()) }
        }
    }

    @Test
    fun `generates values for negative tests`() {
        val negativeTypes = CsvPattern(NumberPattern()).negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(negativeTypes).hasSize(3)
    }

    @Test
    fun `generates values for tests`() {
        assertThat(CsvPattern(NumberPattern()).newBasedOn(Row(), Resolver()).map { it.value }.toList()).satisfies(Consumer {
            assertThat(it).hasSize(1)
            assertThat(it.first()).isInstanceOf(CsvPattern::class.java)
            assertThat(it.first().pattern).isInstanceOf(NumberPattern::class.java)
        })
    }

    @Test
    fun `generates values for backward compatibility check`() {
        assertThat(CsvPattern(NumberPattern()).newBasedOn(Resolver()).toList()).satisfies(Consumer {
            assertThat(it).hasSize(1)
            assertThat(it.first()).isInstanceOf(CsvPattern::class.java)
            assertThat(it.first().pattern).isInstanceOf(NumberPattern::class.java)
        })
    }

    private val contract = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers: []
paths:
  /hello:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - in: query
          name: data
          schema:
            type: array
            items:
              type: integer
      responses:
        '200':
          description: Says hello
          content:
            application/json:
              schema:
                type: string
        """.trimIndent(), "").toFeature()

    @Test
    fun `should read array in query params as CsvString type`() {
        val queryParameters = QueryParameters(paramPairs = listOf("data" to "1", "data" to "2", "data" to "3"))
        val result: Result = contract.scenarios.first().matches(HttpRequest("GET", "/hello", queryParams = queryParameters), emptyMap())
        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should stub out CsvString type`() {
        val queryParameters = QueryParameters(paramPairs = listOf("data" to "1", "data" to "2", "data" to "3"))
        val request = HttpRequest("GET", "/hello", queryParams = queryParameters)
        val stub = contract.matchingStub(request, HttpResponse.OK)

        assertThat(stub.requestType.matches(request, Resolver())).isInstanceOf(Success::class.java)

        HttpStub(listOf(contract), listOf(stub)).use {
            assertDoesNotThrow {
                it.setExpectation(ScenarioStub(request, HttpResponse.OK))
            }
        }
    }

    @Test
    fun `should generate tests with CsvString type`() {
        val params = mutableListOf<String>()
        var count = 0

        contract.executeTests(object: TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                count ++
                println(request.toLogString())
                params.addAll(request.queryParams.keys)
                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }

        })

        println(count)
        println(params)

        assertThat(params).isEqualTo(listOf("data"))
        assertThat(count).isEqualTo(2)
    }

    @Test
    fun `old and new are backward compatible when the csv type has not changed`() {
        val result = testBackwardCompatibility(contract, contract)

        assertThat(result.success()).isTrue()
    }

    @Test
    fun `old and new are NOT backward compatible when the csv type has changed`() {
        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers: []
paths:
  /hello:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - in: query
          name: data
          schema:
            type: array
            items:
              type: boolean
      responses:
        '200':
          description: Says hello
          content:
            application/json:
              schema:
                type: string
        """.trimIndent(), ""
        ).toFeature()

        val result = testBackwardCompatibility(contract, newContract)

        assertThat(result.success()).isFalse()
    }

    @Test
    fun `old and new are NOT backward compatible when the entire type has changed`() {
        val newContract = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers: []
paths:
  /hello:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - in: query
          name: data
          schema:
            type: boolean
      responses:
        '200':
          description: Says hello
          content:
            application/json:
              schema:
                type: string
        """.trimIndent(), "").toFeature()

        val result = testBackwardCompatibility(contract, newContract)

        assertThat(result.success()).isFalse()
    }


    @Test
    @Tag(GENERATION)
    fun `negative patterns should be generated`() {
        val result = StringPattern().negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder(
            "null",
            "number",
            "boolean"
        )
    }
}