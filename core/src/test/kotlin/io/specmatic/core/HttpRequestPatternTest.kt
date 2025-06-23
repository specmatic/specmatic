package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import java.net.URI

internal class HttpRequestPatternTest {
    @Test
    fun `should not match when url does not match`() {
        val httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern(URI("/matching_path")))
        val httpRequest = HttpRequest().updateWith(URI("/unmatched_path"))
        httpRequestPattern.matches(httpRequest, Resolver()).let {
            assertThat(it).isInstanceOf(Failure::class.java)
            assertThat((it as Failure).toMatchFailureDetails()).isEqualTo(MatchFailureDetails(
                listOf("REQUEST", "PARAMETERS.PATH (/unmatched_path)"), listOf("""Expected "matching_path", actual was "unmatched_path""""))
            )
        }
    }

    @Test
    fun `should not match when method does not match`() {
        val httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern(URI("/matching_path")),
                method = "POST")
        val httpRequest = HttpRequest()
            .updateWith(URI("/matching_path"))
            .updateMethod("GET")
        httpRequestPattern.matches(httpRequest, Resolver()).let {
            assertThat(it is Failure).isTrue()
            assertThat((it as Failure).toMatchFailureDetails()).isEqualTo(MatchFailureDetails(listOf("REQUEST", "METHOD"), listOf("Expected POST, actual was GET")))
        }
    }

    @Test
    fun `should not match when body does not match`() {
        val httpRequestPattern =
                HttpRequestPattern(
                        httpPathPattern = buildHttpPathPattern(URI("/matching_path")),
                        method = "POST",
                        body = parsedPattern("""{"name": "Hari"}"""))
        val httpRequest = HttpRequest()
            .updateWith(URI("/matching_path"))
            .updateMethod("POST")
            .updateBody("""{"unmatchedKey": "unmatchedValue"}""")
        httpRequestPattern.matches(httpRequest, Resolver()).let {
            assertThat(it).isInstanceOf(Failure::class.java)
            assertThat((it as Failure).toMatchFailureDetails()).isEqualTo(MatchFailureDetails(listOf("REQUEST", "BODY", "name"), listOf("Expected key named \"name\" was missing")))
        }
    }

    @Test
    fun `should match when request matches url, method and body`() {
        val httpRequestPattern = HttpRequestPattern(
                httpPathPattern =  buildHttpPathPattern(URI("/matching_path")),
                method = "POST",
                body = parsedPattern("""{"name": "Hari"}"""))
        val httpRequest = HttpRequest()
            .updateWith(URI("/matching_path"))
            .updateMethod("POST")
            .updateBody("""{"name": "Hari"}""")
        httpRequestPattern.matches(httpRequest, Resolver()).let {
            assertThat(it).isInstanceOf(Success::class.java)
        }
    }

    @Test
    fun `a clone request pattern request should include the headers specified`() {
        val pattern = HttpRequestPattern(
            headersPattern = HttpHeadersPattern(mapOf("Test-Header" to stringToPattern("(string)", "Test-Header"))),
            httpPathPattern = buildHttpPathPattern(URI("/")),
            method = "GET"
        )

        val newPatterns = pattern.newBasedOn(Row(), Resolver(), 200).map { it.value }.toList()
        assertEquals("(string)", newPatterns[0].headersPattern.pattern["Test-Header"].toString())
    }

    @Test
    fun `a 200 request with an optional header should result in 2 options for newBasedOn`() {
        val requests = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern(URI("/")),
            headersPattern = HttpHeadersPattern(mapOf("X-Optional?" to StringPattern()))
        ).newBasedOn(Row(), Resolver(), 200).map { it.value }.toList()

        assertThat(requests).hasSize(2)

        val flags = requests.map {
            when {
                it.headersPattern.pattern.containsKey("X-Optional") -> "with"
                else -> "without"
            }
        }

        flagsContain(flags, listOf("with", "without"))
    }

    @Test
    fun `a 400 request with an optional header should result in 1 options for newBasedOn`() {
        val requests = HttpRequestPattern(
            method = "GET",
                httpPathPattern = buildHttpPathPattern(URI("/")),
                headersPattern = HttpHeadersPattern(mapOf("X-Optional?" to StringPattern()))
        ).newBasedOn(Row(), Resolver(), 400).map { it.value }.toList()

        assertThat(requests).hasSize(1)

        val flags = requests.map {
            when {
                it.headersPattern.pattern.containsKey("X-Optional") -> "with"
                else -> "without"
            }
        }

        flagsContain(flags, listOf("without"))
    }

    @Test
    fun `a 500 request with an optional header should result in 1 options for newBasedOn`() {
        val requests = HttpRequestPattern(
            method = "GET",
                httpPathPattern = buildHttpPathPattern(URI("/")),
                headersPattern = HttpHeadersPattern(mapOf("X-Optional?" to StringPattern()))
        ).newBasedOn(Row(), Resolver(), 500).map { it.value }.toList()

        assertThat(requests).hasSize(1)

        val flags = requests.map {
            when {
                it.headersPattern.pattern.containsKey("X-Optional") -> "with"
                else -> "without"
            }
        }

        flagsContain(flags, listOf("without"))
    }

    @Test
    fun `number bodies should match numerical strings`() {
        val requestPattern = HttpRequestPattern(method = "GET", httpPathPattern = buildHttpPathPattern("/"), body = NumberPattern())
        val request = HttpRequest("GET", path = "/", body = StringValue("10"))

        assertThat(requestPattern.matches(request, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `boolean bodies should match boolean strings`() {
        val requestPattern = HttpRequestPattern(method = "GET", httpPathPattern = buildHttpPathPattern("/"), body = BooleanPattern())
        val request = HttpRequest("GET", path = "/", body = StringValue("true"))

        assertThat(requestPattern.matches(request, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `boolean bodies should not match non-boolean strings`() {
        val requestPattern = HttpRequestPattern(method = "GET", httpPathPattern = buildHttpPathPattern("/"), body = BooleanPattern())
        val request = HttpRequest("GET", path = "/", body = StringValue("10"))

        assertThat(requestPattern.matches(request, Resolver())).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `integer bodies should not match non-integer strings`() {
        val requestPattern = HttpRequestPattern(method = "GET", httpPathPattern = buildHttpPathPattern("/"), body = NumberPattern())
        val request = HttpRequest("GET", path = "/", body = StringValue("not a number"))

        assertThat(requestPattern.matches(request, Resolver())).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `request with multiple parts and no optional values should result in just one test for the whole`() {
        val parts = listOf(
            MultiPartContentPattern(
                "data1",
                StringPattern(),
            ), MultiPartContentPattern("data2", StringPattern())
        )
        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern("/"),
            multiPartFormDataPattern = parts
        )
        val patterns = requestPattern.newBasedOn(Row(), Resolver()).map { it.value }.toList()

        assertThat(patterns).hasSize(1)

        assertThat(patterns.single().multiPartFormDataPattern).isEqualTo(parts)
    }

    @Test
    fun `request with an optional part should result in two requests`() {
        val part = MultiPartContentPattern("data?", StringPattern())

        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern("/"),
            multiPartFormDataPattern = listOf(part)
        )
        val patterns = requestPattern.newBasedOn(Row(), Resolver()).map { it.value }.toList()

        assertThat(patterns).hasSize(2)

        assertThat(patterns).contains(
            HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/"),
                multiPartFormDataPattern = emptyList()
            )
        )
        assertThat(patterns).contains(
            HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/"),
                multiPartFormDataPattern = listOf(part.nonOptional())
            )
        )
    }

    @Test
    fun `request with a part json body with a key in a row should result in a request with the row value`() {
        val part = MultiPartContentPattern("data", parsedPattern("""{"name": "(string)"}"""))
        val example = Row(listOf("name"), listOf("John Doe"))

        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern("/"),
            multiPartFormDataPattern = listOf(part)
        )
        val patterns = requestPattern.newBasedOn(example, Resolver()).map { it.value }.toList()

        assertThat(patterns).hasSize(1)

        val expectedPattern = HttpRequestPattern(
            method = "GET", httpPathPattern = buildHttpPathPattern("/"), multiPartFormDataPattern = listOf(
                MultiPartContentPattern(
                    "data",
                    toJSONObjectPattern(mapOf("name" to ExactValuePattern(StringValue("John Doe")))),
                )
            )
        )
        assertThat(patterns.single()).isEqualTo(expectedPattern)
    }

    @Test
    fun `request having a part name the same as a key in a row should result in a request with a part having the specified value`() {
        val part = MultiPartContentPattern("name", StringPattern())
        val example = Row(listOf("name"), listOf("John Doe"))

        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern("/"),
            multiPartFormDataPattern = listOf(part)
        )
        val patterns = requestPattern.newBasedOn(example, Resolver()).map { it.value }.toList()

        assertThat(patterns).hasSize(1)

        val expectedPattern = HttpRequestPattern(
            method = "GET", httpPathPattern = buildHttpPathPattern("/"), multiPartFormDataPattern = listOf(
                MultiPartContentPattern(
                    "name",
                    ExactValuePattern(StringValue("John Doe")),
                )
            )
        )
        assertThat(patterns.single()).isEqualTo(expectedPattern)
    }

    @Test
    fun `request having an optional part name the same as a key in a row should result in a request with a part having the specified value`() {
        val part = MultiPartContentPattern("name?", StringPattern())
        val example = Row(listOf("name"), listOf("John Doe"))

        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern("/"),
            multiPartFormDataPattern = listOf(part)
        )
        val patterns = requestPattern.newBasedOn(example, Resolver()).map { it.value }.toList()

        assertThat(patterns).hasSize(1)

        val expectedPattern = HttpRequestPattern(
            method = "GET", httpPathPattern = buildHttpPathPattern("/"), multiPartFormDataPattern = listOf(
                MultiPartContentPattern(
                    "name",
                    ExactValuePattern(StringValue("John Doe")),
                )
            )
        )
        assertThat(patterns.single()).isEqualTo(expectedPattern)
    }

    @Test
    fun `request type having an optional part name should match a request in which the part is missing`() {
        val part = MultiPartContentPattern("name?", StringPattern())

        val requestType = HttpRequestPattern(method = "GET", httpPathPattern = buildHttpPathPattern("/"), multiPartFormDataPattern = listOf(part))

        val request = HttpRequest("GET", "/")

        assertThat(requestType.matches(request, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should generate a request with an array value if the array is in an example`() {
        val example = Row(listOf("csv"), listOf("[1, 2, 3]"))

        val type = parsedPattern("""{"csv": "(number*)"}""")
        val newTypes = type.newBasedOn(example, Resolver()).map { it.value }.toList()

        assertThat(newTypes).hasSize(1)

        val newType = newTypes.single() as JSONObjectPattern

        assertThat(newType.pattern.getValue("csv")).isEqualTo(
            ExactValuePattern(
                JSONArrayValue(
                    listOf(
                        NumberValue(1),
                        NumberValue(2),
                        NumberValue(3)
                    )
                )
            )
        )
    }

    @Test
    fun `should generate a request with an object value if the object is in an example`() {
        val example = Row(listOf("data"), listOf("""{"one": 1}"""))

        val type = parsedPattern("""{"data": "(Data)"}""")
        val newTypes = type.newBasedOn(
            example,
            Resolver(newPatterns = mapOf("(Data)" to toTabularPattern(mapOf("one" to NumberPattern()))))
        ).map { it.value }.toList()

        assertThat(newTypes).hasSize(1)

        val newType = newTypes.single() as JSONObjectPattern

        assertThat(newType.pattern.getValue("data")).isEqualTo(
            ExactValuePattern(
                JSONObjectValue(
                    mapOf(
                        "one" to NumberValue(
                            1
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `should generate a request with an array body if the array is in an example`() {
        val example = Row(listOf("body"), listOf("[1, 2, 3]"))

        val requestType =
            HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), body = parsedPattern("(body: RequestBody)"))
        val newRequestTypes = requestType.newBasedOn(
            example,
            Resolver(newPatterns = mapOf("(RequestBody)" to parsedPattern("""(number*)""")))
        ).map { it.value }.toList()

        assertThat(newRequestTypes).hasSize(1)

        val newRequestType = newRequestTypes.single()
        val requestBodyType = newRequestType.body as ExactValuePattern

        assertThat(requestBodyType).isEqualTo(
            ExactValuePattern(
                JSONArrayValue(
                    listOf(
                        NumberValue(1),
                        NumberValue(2),
                        NumberValue(3)
                    )
                )
            )
        )
    }

    @Test
    fun `should generate a request with an object body if the object is in an example`() {
        val example = Row(listOf("body"), listOf("""{"one": 1}"""))

        val requestType =
            HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), body = parsedPattern("(body: RequestBody)"))
        val newRequestTypes = requestType.newBasedOn(
            example,
            Resolver(newPatterns = mapOf("(RequestBody)" to toTabularPattern(mapOf("one" to NumberPattern()))))
        ).map { it.value }.toList()

        assertThat(newRequestTypes).hasSize(1)

        val newRequestType = newRequestTypes.single()
        val requestBodyType = newRequestType.body as ExactValuePattern

        assertThat(requestBodyType).isEqualTo(ExactValuePattern(JSONObjectValue(mapOf("one" to NumberValue(1)))))
    }

    @Test
    fun `should generate a stub request pattern from an http request in which the query params are not optional`() {
        val requestType = HttpRequestPattern(method = "GET", httpPathPattern = HttpPathPattern(pathToPattern("/"), "/"), httpQueryParamPattern = HttpQueryParamPattern(mapOf("status" to QueryParameterScalarPattern(StringPattern()))))
        val newRequestType = requestType.generate(HttpRequest("GET", "/", queryParametersMap = mapOf("status" to "available")), Resolver())

        assertThat(newRequestType.httpQueryParamPattern.queryPatterns.keys.sorted()).isEqualTo(listOf("status"))

    }

    @Test
    fun `form field of type json in string should match a form field value of type json in string`() {
        val customerType: Pattern = TabularPattern(mapOf("id" to NumberPattern()))
        val request = HttpRequest(method = "POST", path = "/", formFields = mapOf("Customer" to """{"id": 10}"""))

        HttpRequestPattern(
            method = "POST",
            httpPathPattern = HttpPathPattern(emptyList(), "/"),
            formFieldsPattern = mapOf("Customer" to PatternInStringPattern(customerType, "(customer)"))
        ).generate(request, Resolver()).let { requestType ->
            val customerFieldType = requestType.formFieldsPattern.getValue("Customer")
            assertThat(customerFieldType).isInstanceOf(PatternInStringPattern::class.java)

            val patternInStringPattern = customerFieldType as PatternInStringPattern
            assertThat(patternInStringPattern.pattern).isInstanceOf(JSONObjectPattern::class.java)

            assertThat(patternInStringPattern.matches(parsedJSON("""{"id": 10}""").toStringValue(), Resolver())).isInstanceOf(
                Success::class.java)
        }
    }

    @Test
    fun `optional form field can be omitted from request`() {
        val request = HttpRequest(method = "POST", path = "/", formFields = mapOf("hello" to """10"""))

        val result = HttpRequestPattern(
            method = "POST",
            httpPathPattern = HttpPathPattern(emptyList(), "/"),
            formFieldsPattern = mapOf("hello" to NumberPattern(), "world?" to NumberPattern())
        ).matches(request, Resolver())

        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `match errors across the request including header and body will be returned`()  {
        val type = HttpRequestPattern(method = "POST", httpPathPattern = buildHttpPathPattern("http://helloworld.com/data"), headersPattern = HttpHeadersPattern(mapOf("X-Data" to NumberPattern())), body = JSONObjectPattern(mapOf("id" to NumberPattern())))
        val request = HttpRequest("POST", "/data", headers = mapOf("X-Data" to "abc123"), body = parsedJSON("""{"id": "abc123"}"""))

        val result = type.matches(request, Resolver())
        val reportText = result.reportString()
        assertThat(reportText).contains(">> REQUEST.PARAMETERS.HEADER.X-Data")
        assertThat(reportText).contains(">> REQUEST.BODY.id")
    }

    @Test
    fun `should lower case header keys while loading stub data`()  {
        val type = HttpRequestPattern(method = "POST", httpPathPattern = buildHttpPathPattern("http://helloworld.com/data"), headersPattern = HttpHeadersPattern(mapOf("x-data" to StringPattern())), body = JSONObjectPattern(mapOf("id" to NumberPattern())))
        val request = HttpRequest("POST", "/data", headers = mapOf("X-Data" to "abc123"), body = parsedJSON("""{"id": "abc123"}"""))

        val httpRequestPattern = type.generate(request, Resolver())
        assertThat(httpRequestPattern.headersPattern.pattern["x-data"].toString()).isEqualTo("abc123")
    }

    @Nested
    inner class FormFieldMatchReturnsAllErrors {
        val request = HttpRequest(method = "POST", path = "/", formFields = mapOf("hello" to "abc123"))

        val result = HttpRequestPattern(
            method = "POST",
            httpPathPattern = HttpPathPattern(emptyList(), "/"),
            formFieldsPattern = mapOf("hello" to NumberPattern(), "world" to NumberPattern())
        ).matches(request, Resolver())

        private val reportText = result.reportString()

        @Test
        fun `returns all form field errors`() {
            result as Failure
            assertThat(result.toMatchFailureDetailList()).hasSize(2)
        }

        @Test
        fun `error fields are referenced in the report`() {
            assertThat(reportText).contains(""">> REQUEST.FORM-FIELDS.hello""")
            assertThat(reportText).contains(""">> REQUEST.FORM-FIELDS.world""")
        }

        @Test
        fun `presence errors appear before the payload errors`() {
            assertThat(reportText.indexOf(""">> REQUEST.FORM-FIELDS.world""")).isLessThan(reportText.indexOf(""">> REQUEST.FORM-FIELDS.hello"""))
        }
    }

    @Nested
    inner class MultiPartMatchReturnsAllErrors {
        private val parts = listOf(
            MultiPartContentPattern("data1", NumberPattern()),
            MultiPartContentPattern("data2", NumberPattern()))
        private val requestPattern = HttpRequestPattern(method = "POST", httpPathPattern = buildHttpPathPattern("/"), multiPartFormDataPattern = parts)
        val request = HttpRequest("POST", "/", multiPartFormData = listOf(MultiPartContentValue("data1", StringValue("abc123"))))

        val result = requestPattern.matches(request, Resolver())
        private val reportText = result.reportString()

        @Test
        fun `returns all multipart field errors`() {
            result as Failure
            assertThat(result.toMatchFailureDetailList()).hasSize(2)
        }

        @Test
        fun `error fields are referenced in the report`() {
            assertThat(reportText).contains(""">> REQUEST.MULTIPART-FORMDATA.data1""")
            assertThat(reportText).contains(""">> REQUEST.MULTIPART-FORMDATA.data2""")
        }

        @Test
        fun `presence errors appear before the payload errors`() {
            assertThat(reportText.indexOf(""">> REQUEST.MULTIPART-FORMDATA.data2""")).isLessThan(reportText.indexOf(""">> REQUEST.MULTIPART-FORMDATA.data1"""))
        }
    }

    @Test
    fun `should not generate test request for generative tests more than once`() {
        val pattern = HttpRequestPattern(
            method = "POST",
            httpPathPattern = buildHttpPathPattern("http://helloworld.com/data"),
            body = JSONObjectPattern(mapOf("id" to NumberPattern()))
        )

        val row = Row(listOf("(REQUEST-BODY)"), listOf("""{ "id": 10 }"""))
        val patterns =
            pattern.newBasedOn(row, Resolver(generation = GenerativeTestsEnabled(false))).map { it.value }.toList()

        assertThat(patterns).hasSize(1)
    }

    @Test
    fun `content-type should be sent when available`() {
        val httpRequestPattern = HttpRequestPattern(
            headersPattern = HttpHeadersPattern(contentType = "application/json"),
            method = "POST",
            httpPathPattern = buildHttpPathPattern(URI("/matching_path")),
            body = StringPattern()
        )
        val httpRequest: HttpRequest = httpRequestPattern.generate(Resolver())
        assertThat(httpRequest.headers[CONTENT_TYPE]).isEqualTo("application/json")
    }

    @Test
    fun `comment on enum pattern with generated values should bubble up`() {
        val openApiSpecWithEnumInPOSTBodyAsYAML = """
            openapi: 3.0.0
            info:
              title: Test API
              version: 1.0.0
            paths:
              /:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - type
                            - data
                          properties:
                            type:
                              ${"$"}ref: "#/components/schemas/Item"
                            data:
                              type: string
                  responses:
                    '200':
                      description: OK
            components:
              schemas:
                Item:
                  type: string
                  enum:
                    - gadget
                    - book
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(openApiSpecWithEnumInPOSTBodyAsYAML, "").toFeature().enableGenerativeTesting()

        val negativeTestScenarios = feature.negativeTestScenarios().toList()

        negativeTestScenarios.map { it.second }.filterIsInstance<HasValue<*>>().map { it.value as Scenario }.forEach {
            println(it.testDescription())
        }

        val testDescriptions = negativeTestScenarios.map { it.second }.filterIsInstance<HasValue<*>>().map { it.value as Scenario }.map { it.testDescription() }

        assertThat(testDescriptions.count { it.matches(Regex("^.*REQUEST.BODY.*enum.*$")) }).isEqualTo(6)
    }

    @Test
    fun `comment on enum pattern in query param generated values should bubble up`() {
        val openApiYAMLSpecWithEnumInQueryParamAs = """
            openapi: 3.0.0
            info:
              title: Test API
              version: 1.0.0
            paths:
              /:
                get:
                  parameters:
                    - name: type
                      in: query
                      required: true
                      schema:
                        type: string
                        enum:
                          - gadget
                          - book
                    - name: id
                      in: query
                      required: true
                      schema:
                        type: number
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(openApiYAMLSpecWithEnumInQueryParamAs, "").toFeature().enableGenerativeTesting()

        val negativeTestScenarios = feature.negativeTestScenarios().toList()

        negativeTestScenarios.map { it.second }.filterIsInstance<HasValue<*>>().map { it.value as Scenario }.forEach {
            println(it.testDescription())
        }


        val testDescriptions = negativeTestScenarios.map { it.second }.filterIsInstance<HasValue<*>>().map { it.value as Scenario }.map { it.testDescription() }

        assertThat(testDescriptions.count { it.matches(Regex("^.*PARAMETERS.QUERY.*enum.*$")) }).isEqualTo(4)
    }

    @Test
    fun `comment on enum pattern in header generated values should bubble up`() {
        val openApiYAMLSpecWithEnumInQueryParamAs = """
            openapi: 3.0.0
            info:
              title: Test API
              version: 1.0.0
            paths:
              /:
                get:
                  parameters:
                    - name: type
                      in: header
                      required: true
                      schema:
                        type: string
                        enum:
                          - gadget
                          - book
                    - name: id
                      in: header
                      required: true
                      schema:
                        type: number
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(openApiYAMLSpecWithEnumInQueryParamAs, "").toFeature().enableGenerativeTesting()

        val negativeTestScenarios = feature.negativeTestScenarios().toList()

        val testDescriptions = negativeTestScenarios.map { it.second }.filterIsInstance<HasValue<*>>().map { it.value as Scenario }.map { it.testDescription() }

        testDescriptions.forEach {
            println(it)
        }

        assertThat(testDescriptions.count { it.matches(Regex("^.*HEADER.*enum.*$")) }).isEqualTo(4)
    }

    @Nested
    inner class GenerateV2Tests {
        @Test
        fun `should generate httpRequests using discriminator values where body is a ListPattern with discriminator`() {
            val savingsAccountPattern = JSONObjectPattern(
                pattern = mapOf(
                    "@type" to ExactValuePattern(StringValue("savings"), discriminator = true),
                    "accountId" to StringPattern(),
                    "accountHolderName" to StringPattern(),
                    "balance" to NumberPattern(),
                    "minimumBalance" to NumberPattern()
                )
            )

            val currentAccountPattern = JSONObjectPattern(
                pattern = mapOf(
                    "@type" to ExactValuePattern(StringValue("current"), discriminator = true),
                    "accountId" to StringPattern(),
                    "accountHolderName" to StringPattern(),
                    "balance" to NumberPattern(),
                    "overdraftLimit" to NumberPattern()
                )
            )

            val listPattern = ListPattern(
                AnyPattern(
                    listOf(savingsAccountPattern, currentAccountPattern),
                    discriminatorProperty = "@type",
                    discriminatorValues = setOf("savings", "current")
                )
            )
            val httpRequestPattern = HttpRequestPattern(
                body = listPattern,
                method = "POST",
                httpPathPattern = HttpPathPattern(emptyList(), "/account")
            )

            val requests =  httpRequestPattern.generateV2(Resolver())

            assertThat(requests.size).isEqualTo(2)
            assertThat(requests.map { it.discriminatorValue }).containsExactlyInAnyOrder("savings", "current")

            val savingsAccountRequestBody = (requests.first { it.discriminatorValue == "savings" }.value.body as JSONArrayValue).list.first() as JSONObjectValue
            val currentAccountRequestBody = (requests.first { it.discriminatorValue == "current" }.value.body as JSONArrayValue).list.first() as JSONObjectValue
            assertThat(savingsAccountRequestBody.jsonObject["@type"]?.toStringLiteral()).isEqualTo("savings")
            assertThat(currentAccountRequestBody.jsonObject["@type"]?.toStringLiteral()).isEqualTo("current")
        }

        @Test
        fun `should generate httpRequests using discriminator values where body is a non-list pattern with discriminator`() {
            val savingsAccountPattern = JSONObjectPattern(
                pattern = mapOf(
                    "@type" to ExactValuePattern(StringValue("savings"), discriminator = true),
                    "accountId" to StringPattern(),
                    "accountHolderName" to StringPattern(),
                    "balance" to NumberPattern(),
                    "minimumBalance" to NumberPattern()
                )
            )

            val currentAccountPattern = JSONObjectPattern(
                pattern = mapOf(
                    "@type" to ExactValuePattern(StringValue("current"), discriminator = true),
                    "accountId" to StringPattern(),
                    "accountHolderName" to StringPattern(),
                    "balance" to NumberPattern(),
                    "overdraftLimit" to NumberPattern()
                )
            )

            val bodyPattern = AnyPattern(
                listOf(savingsAccountPattern, currentAccountPattern),
                discriminatorProperty = "@type",
                discriminatorValues = setOf("savings", "current")
            )

            val httpRequestPattern = HttpRequestPattern(
                body = bodyPattern,
                method = "POST",
                httpPathPattern = HttpPathPattern(emptyList(), "/account")
            )

            val requests =  httpRequestPattern.generateV2(Resolver())

            assertThat(requests.size).isEqualTo(2)
            assertThat(requests.map { it.discriminatorValue }).containsExactlyInAnyOrder("savings", "current")

            val savingsAccountRequestBody = (requests.first { it.discriminatorValue ==  "savings"}.value.body as JSONObjectValue)
            val currentAccountRequestBody = (requests.first { it.discriminatorValue ==  "current"}.value.body as JSONObjectValue)
            assertThat(savingsAccountRequestBody.jsonObject["@type"]?.toStringLiteral()).isEqualTo("savings")
            assertThat(currentAccountRequestBody.jsonObject["@type"]?.toStringLiteral()).isEqualTo("current")
        }
    }

    @Test
    fun `should fallback to string value if parse fails when converting request to pattern`() {
        val httpRequestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern(URI("/(id:uuid)")),
            headersPattern = HttpHeadersPattern(mapOf("key" to DatePattern)),
            httpQueryParamPattern = HttpQueryParamPattern(mapOf("key" to QueryParameterScalarPattern(DateTimePattern))),
            body = JSONObjectPattern(mapOf("key" to EmailPattern()))
        )
        val httpRequest = HttpRequest(
            path = "/invalidUUID",
            method = "GET",
            headers = mapOf("key" to "invalidDate"),
            queryParams = QueryParameters(mapOf("key" to "invalidDateTime")),
            body = JSONObjectValue(mapOf("key" to StringValue("invalidEmail")))
        )
        val newRequestPattern = httpRequestPattern.generate(httpRequest, Resolver())

        assertThat(newRequestPattern.httpPathPattern).isEqualTo(buildHttpPathPattern("/invalidUUID"))
        assertThat(newRequestPattern.method).isEqualTo("GET")
        assertThat(newRequestPattern.headersPattern.pattern).isEqualTo(mapOf("key" to "invalidDate".toExactValuePattern()))
        assertThat(newRequestPattern.body).isEqualTo(JSONObjectPattern(mapOf("key" to "invalidEmail".toExactValuePattern())))
        assertThat(newRequestPattern.httpQueryParamPattern.queryPatterns).isEqualTo(mapOf(
            "key" to QueryParameterScalarPattern("invalidDateTime".toExactValuePattern())
        ))
    }

    @Test
    fun `should return NoBodyPattern when request body is EmptyString`() {
        val httpRequestPattern = HttpRequestPattern(
            method = "GET", httpPathPattern = buildHttpPathPattern("/"),
            body = JSONObjectPattern(mapOf("key" to EmailPattern()))
        )
        val httpRequest = HttpRequest(path = "/", method = "GET")
        val newRequestPattern = httpRequestPattern.generate(httpRequest, Resolver())

        assertThat(newRequestPattern.body).isEqualTo(EmptyStringPattern)
    }

    private fun String.toExactValuePattern(): ExactValuePattern = ExactValuePattern(StringValue(this))
}
