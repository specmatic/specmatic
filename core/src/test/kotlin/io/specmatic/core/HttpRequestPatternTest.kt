package io.specmatic.core

import io.specmatic.conversions.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.*
import io.specmatic.core.substitution.SubstitutionImpl
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.toViolationReportString
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.util.stream.Stream

internal class HttpRequestPatternTest {
    @Test
    fun `should not match when url does not match`() {
        val httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern(URI("/matching_path")))
        val httpRequest = HttpRequest().updateWith(URI("/unmatched_path"))
        httpRequestPattern.matches(httpRequest, Resolver()).let {
            assertThat(it).isInstanceOf(Failure::class.java)
            assertThat((it as Failure).toMatchFailureDetails()).isEqualTo(MatchFailureDetails(
                listOf("REQUEST", "PATH"),
                listOf("Failed to extract segments for /matching_path from /unmatched_path")
            ))
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
            assertThat((it as Failure).toMatchFailureDetails()).isEqualTo(MatchFailureDetails(listOf("REQUEST", "METHOD"), listOf(DefaultMismatchMessages.mismatchMessage("POST", "GET"))))
        }
    }

    @Test
    fun `matchesPathStructureAndMethod should succeed when path structure matches but path param does not`() {
        val pattern = HttpRequestPattern(
            httpPathPattern = buildHttpPathPattern("/pets/(id:number)"),
            method = "GET"
        )

        val request = HttpRequest(method = "GET", path = "/pets/abc")

        assertThat(pattern.matchesPathAndMethod(request, Resolver()))
            .isInstanceOf(Failure::class.java)
        assertThat(pattern.matchesPathStructureAndMethod(request, Resolver()))
            .isInstanceOf(Success::class.java)
    }

    @Test
    fun `matchesPathStructureAndMethod should fail when method does not match`() {
        val pattern = HttpRequestPattern(
            httpPathPattern = buildHttpPathPattern("/pets/(id:number)"),
            method = "GET"
        )

        val request = HttpRequest(method = "POST", path = "/pets/123")

        assertThat(pattern.matchesPathStructureAndMethod(request, Resolver()))
            .isInstanceOf(Failure::class.java)
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
            assertThat((it as Failure).toMatchFailureDetails()).isEqualTo(MatchFailureDetails(listOf("REQUEST", "BODY", "name"), listOf(DefaultMismatchMessages.expectedKeyWasMissing("property", "name"))))
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
        val newRequestType = requestType.generateExactHttpRequestPatternFrom(HttpRequest("GET", "/", queryParametersMap = mapOf("status" to "available")), Resolver())

        assertThat(newRequestType.httpQueryParamPattern.queryPatterns.keys.sorted()).isEqualTo(listOf("status"))

    }

    @Test
    fun `should preserve additional query params when generating an exact request pattern`() {
        val requestType = HttpRequestPattern(
            method = "GET",
            httpPathPattern = HttpPathPattern(pathToPattern("/"), "/"),
            httpQueryParamPattern = HttpQueryParamPattern(mapOf("status" to QueryParameterScalarPattern(StringPattern())))
        )

        val request = HttpRequest(
            path = "/",
            method = "GET",
            queryParametersMap = mapOf("status" to "available", "extra" to "extra-value")
        )

        val newRequestType = requestType.generateExactHttpRequestPatternFrom(request, Resolver())
        assertThat(newRequestType.httpQueryParamPattern.queryPatterns).containsKeys("status", "extra")
        assertThat(newRequestType.httpQueryParamPattern.queryPatterns["extra"])
            .isEqualTo(QueryParameterScalarPattern(ExactValuePattern(StringValue("extra-value"))))
    }

    @Test
    fun `should preserve query parameter metadata when generating an exact request pattern`() {
        val collisionGroup = QueryParameterCollisionGroup(
            wireKey = "age",
            owners = listOf(
                QueryParameterCollisionOwner(
                    wireKey = "age",
                    sourceName = "info.age",
                    kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                    pattern = QueryParameterScalarPattern(NumberPattern()),
                    required = false,
                    parameterName = "info",
                    propertyName = "age"
                ),
                QueryParameterCollisionOwner(
                    wireKey = "age",
                    sourceName = "age",
                    kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                    pattern = QueryParameterScalarPattern(StringPattern()),
                    required = false,
                    parameterName = "age"
                )
            ),
            authoritativeOwner = QueryParameterCollisionOwner(
                wireKey = "age",
                sourceName = "info.age",
                kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                pattern = QueryParameterScalarPattern(NumberPattern()),
                required = false,
                parameterName = "info",
                propertyName = "age"
            )
        )
        val formExplodedObjectQueryParam = FormExplodedObjectQueryParam(
            parameterName = "info",
            required = false,
            propertyKeys = setOf("age", "name"),
            requiredPropertyKeys = setOf("name")
        )
        val queryPattern = HttpQueryParamPattern(
            queryPatterns = mapOf(
                "age?" to QueryParameterScalarPattern(StringPattern()),
                "name?" to QueryParameterScalarPattern(StringPattern())
            ),
            additionalProperties = StringPattern(),
            extensibleQueryParams = true,
            formExplodedObjectQueryParams = listOf(formExplodedObjectQueryParam),
            parameterPointers = mapOf("age" to "/paths/~1data/get/parameters/0/schema/properties/age"),
            collisionGroupsByWireKey = mapOf("age" to collisionGroup)
        )
        val requestType = HttpRequestPattern(
            method = "GET",
            httpPathPattern = HttpPathPattern(pathToPattern("/"), "/"),
            httpQueryParamPattern = queryPattern
        )

        val exactRequestType = requestType.generateExactHttpRequestPatternFrom(
            HttpRequest("GET", "/", queryParams = QueryParameters(mapOf("age" to "45", "name" to "Jane"))),
            Resolver()
        )

        assertThat(exactRequestType.httpQueryParamPattern.additionalProperties).isEqualTo(StringPattern())
        assertThat(exactRequestType.httpQueryParamPattern.extensibleQueryParams).isTrue()
        assertThat(exactRequestType.httpQueryParamPattern.formExplodedObjectQueryParams).containsExactly(formExplodedObjectQueryParam)
        assertThat(exactRequestType.httpQueryParamPattern.parameterPointers).containsEntry("age", "/paths/~1data/get/parameters/0/schema/properties/age")
        assertThat(exactRequestType.httpQueryParamPattern.collisionGroupsByWireKey).containsEntry("age", collisionGroup)
    }

    @Test
    fun `should complain for additional query params when generating an exact request pattern and value does not match additional properties`() {
        val requestType = HttpRequestPattern(
            method = "GET",
            httpPathPattern = HttpPathPattern(pathToPattern("/"), "/"),
            httpQueryParamPattern = HttpQueryParamPattern(
                additionalProperties = NumberPattern(),
                queryPatterns = mapOf("status" to QueryParameterScalarPattern(StringPattern())),
            )
        )

        val request = HttpRequest(
            path = "/",
            method = "GET",
            queryParametersMap = mapOf("status" to "available", "extra" to "not-a-number")
        )

        val exception = assertThrows<ContractException> {
            requestType.generateExactHttpRequestPatternFrom(request, Resolver())
        }

        assertThat(exception.report()).isEqualToNormalizingWhitespace(toViolationReportString(
            breadCrumb = "REQUEST.URL",
            details = """
            Expected type number, actual was value "not-a-number" of type string
            Expected number, actual was "not-a-number"
            """.trimIndent(),
            StandardRuleViolation.TYPE_MISMATCH
        ))
    }

    @Test
    fun `form field of type json in string should match a form field value of type json in string`() {
        val customerType: Pattern = TabularPattern(mapOf("id" to NumberPattern()))
        val request = HttpRequest(method = "POST", path = "/", formFields = mapOf("Customer" to """{"id": 10}"""))

        HttpRequestPattern(
            method = "POST",
            httpPathPattern = HttpPathPattern(emptyList(), "/"),
            formFieldsPattern = mapOf("Customer" to PatternInStringPattern(customerType, "(customer)"))
        ).generateExactHttpRequestPatternFrom(request, Resolver()).let { requestType ->
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
            httpPathPattern = HttpPathPattern.from("/"),
            formFieldsPattern = mapOf("hello" to NumberPattern(), "world?" to NumberPattern())
        ).matches(request, Resolver())

        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `missing required form fields are reported as missing properties`() {
        val request = HttpRequest(
            method = "POST",
            path = "/",
            formFields = mapOf("grant_type" to "client_credentials", "scope" to "dummy/.default")
        )

        val result = HttpRequestPattern(
            method = "POST",
            httpPathPattern = HttpPathPattern.from("/"),
            formFieldsPattern = mapOf(
                "client_id" to StringPattern(),
                "client_secret" to StringPattern(),
                "grant_type" to StringPattern(),
                "scope" to StringPattern()
            )
        ).matches(request, Resolver())

        assertThat(result).isInstanceOf(Failure::class.java)

        val failureDetails = (result as Failure).toMatchFailureDetailList()
        assertThat(failureDetails.map { it.breadCrumbs.joinToString(".") }).containsExactly(
            "REQUEST.FORM-FIELDS.client_id",
            "REQUEST.FORM-FIELDS.client_secret"
        )
        assertThat(failureDetails.map { it.ruleViolationReport?.ruleViolations }).containsExactly(
            setOf(StandardRuleViolation.REQUIRED_PROPERTY_MISSING),
            setOf(StandardRuleViolation.REQUIRED_PROPERTY_MISSING)
        )
        assertThat(result.reportString()).contains("R2001: Missing required property")
        assertThat(result.reportString()).doesNotContain("R2003: Unknown property")
    }

    @Test
    fun `unknown form fields are reported as unknown properties`() {
        val request = HttpRequest(method = "POST", path = "/", formFields = mapOf("known" to "10", "extra" to "20"))

        val result = HttpRequestPattern(
            method = "POST",
            httpPathPattern = HttpPathPattern.from("/"),
            formFieldsPattern = mapOf("known" to NumberPattern())
        ).matches(request, Resolver())

        assertThat(result).isInstanceOf(Failure::class.java)

        val failureDetails = (result as Failure).toMatchFailureDetailList()
        assertThat(failureDetails.map { it.breadCrumbs.joinToString(".") }).containsExactly("REQUEST.FORM-FIELDS.extra")
        assertThat(failureDetails.single().ruleViolationReport?.ruleViolations).isEqualTo(setOf(StandardRuleViolation.UNKNOWN_PROPERTY))
        assertThat(result.reportString()).contains("R2003: Unknown property")
    }

    @Test
    fun `missing and unknown form fields are reported with distinct rule violations`() {
        val request = HttpRequest(method = "POST", path = "/", formFields = mapOf("known" to "10", "extra" to "20"))

        val result = HttpRequestPattern(
            method = "POST",
            httpPathPattern = HttpPathPattern.from("/"),
            formFieldsPattern = mapOf("known" to NumberPattern(), "missing" to StringPattern())
        ).matches(request, Resolver())

        assertThat(result).isInstanceOf(Failure::class.java)

        val failureDetails = (result as Failure).toMatchFailureDetailList()
        assertThat(failureDetails.map { it.breadCrumbs.joinToString(".") }).containsExactly(
            "REQUEST.FORM-FIELDS.missing",
            "REQUEST.FORM-FIELDS.extra"
        )
        assertThat(failureDetails.map { it.ruleViolationReport?.ruleViolations }).containsExactly(
            setOf(StandardRuleViolation.REQUIRED_PROPERTY_MISSING),
            setOf(StandardRuleViolation.UNKNOWN_PROPERTY)
        )
    }

    @Test
    fun `openapi form-urlencoded requests report missing required form fields as missing properties`() {
        val contract = """
            openapi: 3.0.3
            info:
              title: Token API
              version: '1.0'
            paths:
              /token:
                post:
                  requestBody:
                    required: true
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          type: object
                          required:
                            - client_id
                            - client_secret
                            - grant_type
                          properties:
                            client_id:
                              type: string
                            client_secret:
                              type: string
                            grant_type:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(contract, "").toFeature()
        val request = HttpRequest(
            method = "POST",
            path = "/token",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            formFields = mapOf("grant_type" to "client_credentials")
        )

        val response = feature.lookupResponse(request)

        assertThat(response.status).isEqualTo(400)
        assertThat(response.body.toStringLiteral()).contains("R2001: Missing required property")
        assertThat(response.body.toStringLiteral()).contains("REQUEST.FORM-FIELDS.client_id")
        assertThat(response.body.toStringLiteral()).contains("REQUEST.FORM-FIELDS.client_secret")
        assertThat(response.body.toStringLiteral()).doesNotContain("R2003: Unknown property")
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

        val httpRequestPattern = type.generateExactHttpRequestPatternFrom(request, Resolver())
        assertThat(httpRequestPattern.headersPattern.pattern["x-data"].toString()).isEqualTo("abc123")
    }

    @Nested
    inner class FormFieldMatchReturnsAllErrors {
        val request = HttpRequest(method = "POST", path = "/", formFields = mapOf("hello" to "abc123"))

        val result = HttpRequestPattern(
            method = "POST",
            httpPathPattern = HttpPathPattern.from("/"),
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

    @Test
    fun `generating a request pattern from an http request should also convert in-spec and extra headers to patterns`() {
        val originalRequestPattern = HttpRequestPattern(
            httpPathPattern = buildHttpPathPattern("/"), method = "GET",
            headersPattern = HttpHeadersPattern(pattern = mapOf("X-Test-Header" to StringPattern()), contentType = "application/json"),
            body = JSONObjectPattern(mapOf("key" to StringPattern()))
        )
        val httpRequest = HttpRequest(
            headers = mapOf("X-Test-Header" to "abc123", "X-Extra-Header" to "def456"),
            body = JSONObjectValue(mapOf("key" to StringValue("value")))
        )
        val newRequestPattern = originalRequestPattern.generateExactHttpRequestPatternFrom(httpRequest, Resolver())
        val requestBodyPattern = newRequestPattern.body as JSONObjectPattern

        assertThat(newRequestPattern.headersPattern.pattern).isEqualTo(mapOf(
            "x-test-header" to ExactValuePattern(StringValue("abc123")),
            "x-extra-header" to ExactValuePattern(StringValue("def456"))
        ))
        assertThat(requestBodyPattern.patternForKey("key")).isEqualTo(ExactValuePattern(StringValue("value")))
    }

    @ParameterizedTest
    @MethodSource("headersBasedSecuritySchemesProvider")
    fun `security scheme headers should be preserved when converting headers from http request to exact stub pattern`(securityScheme: OpenAPISecurityScheme) {
        val originalRequestPattern = HttpRequestPattern(
            httpPathPattern = buildHttpPathPattern("/"), method = "GET",
            headersPattern = HttpHeadersPattern(pattern = mapOf("X-Test-Header" to StringPattern()), contentType = "application/json"),
            securitySchemes = listOf(securityScheme)
        )
        val httpRequest = HttpRequest(
            headers = mapOf("X-Test-Header" to "abc123", "X-Extra-Header" to "def456", AUTHORIZATION to "1234")
        )
        val newRequestPattern = originalRequestPattern.generateExactHttpRequestPatternFrom(httpRequest, Resolver())

        assertThat(newRequestPattern.headersPattern.pattern).isEqualTo(mapOf(
            "x-test-header" to ExactValuePattern(StringValue("abc123")),
            "x-extra-header" to ExactValuePattern(StringValue("def456")),
            "authorization" to ExactValuePattern(StringValue("1234"))
        ))
    }

    @Test
    fun `generated security headers should not become exact stub patterns`() {
        val originalRequestPattern = HttpRequestPattern(
            httpPathPattern = buildHttpPathPattern("/"),
            method = "GET",
            securitySchemes = listOf(APIKeyInHeaderSecurityScheme("X-Access-Key", null))
        )
        val generatedRequest = HttpRequest("GET", "/")
            .addSecurityHeader("X-Access-Key", "generated-token")

        val exactRequestPattern = originalRequestPattern.generateExactHttpRequestPatternFrom(
            generatedRequest,
            Resolver()
        )

        assertThat(exactRequestPattern.headersPattern.pattern).doesNotContainKey("x-access-key")
    }

    @Test
    fun `generated security query parameters should not become exact stub patterns`() {
        val securityScheme = APIKeyInQueryParamSecurityScheme("access_key", "generated-token")
        val originalRequestPattern = HttpRequestPattern(
            httpPathPattern = buildHttpPathPattern("/"),
            method = "GET",
            securitySchemes = listOf(securityScheme)
        )
        val generatedRequest = securityScheme.addTo(HttpRequest("GET", "/"), Resolver())

        val exactRequestPattern = originalRequestPattern.generateExactHttpRequestPatternFrom(
            generatedRequest,
            Resolver()
        )

        assertThat(exactRequestPattern.httpQueryParamPattern.queryPatterns).doesNotContainKey("access_key")
    }

    @Test
    fun `security scheme query parameters should remain exact when converting an http request to an exact stub pattern`() {
        val originalRequestPattern = HttpRequestPattern(
            httpPathPattern = buildHttpPathPattern("/"),
            method = "GET",
            securitySchemes = listOf(APIKeyInQueryParamSecurityScheme("access_key", null))
        )
        val exactRequestPattern = originalRequestPattern.generateExactHttpRequestPatternFrom(
            HttpRequest("GET", "/", queryParametersMap = mapOf("access_key" to "expected-query-token")),
            Resolver()
        )

        val matchingResult = exactRequestPattern.matches(
            HttpRequest("GET", "/", queryParametersMap = mapOf("access_key" to "expected-query-token")),
            Resolver()
        )
        val mismatchingResult = exactRequestPattern.matches(
            HttpRequest("GET", "/", queryParametersMap = mapOf("access_key" to "different-query-token")),
            Resolver()
        )

        assertThat(matchingResult).isInstanceOf(Success::class.java)
        assertThat(mismatchingResult).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `should ignore content-type in headers when converting request to pattern`() {
        val originalRequestPattern = HttpRequestPattern(
            httpPathPattern = buildHttpPathPattern("/"), method = "GET",
            headersPattern = HttpHeadersPattern(contentType = "application/json"),
        )
        val httpRequest = HttpRequest(headers = mapOf("Content-Type" to "application/json", "X-Extra-Header" to "def456"))
        val newRequestPattern = originalRequestPattern.generateExactHttpRequestPatternFrom(httpRequest, Resolver())

        assertThat(newRequestPattern.headersPattern.pattern).isEqualTo(mapOf(
            "x-extra-header" to ExactValuePattern(StringValue("def456"))
        ))
        assertThat(newRequestPattern.headersPattern.contentType).isEqualTo("application/json")
    }

    @Test
    fun `negativeBasedOn should use security scheme from row when available`() {
        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern("/pets/(id:number)"),
            securitySchemes = listOf(
                APIKeyInHeaderSecurityScheme("X-Api-Key", null),
                APIKeyInQueryParamSecurityScheme("api_key", null)
            )
        )

        val negativePatterns = requestPattern.negativeBasedOn(
            row = Row(mapOf("id" to "10", "api_key" to "row-token")),
            resolver = Resolver()
        ).map { it.value }.toList()

        assertThat(negativePatterns).isNotEmpty
        assertThat(negativePatterns).allSatisfy { negativePattern ->
            assertThat(negativePattern.httpQueryParamPattern.queryPatterns).containsKey("api_key")
            assertThat(negativePattern.headersPattern.pattern).doesNotContainKey("X-Api-Key")
        }
    }

    @Test
    fun `negativeBasedOn should fallback to first security scheme when row has none`() {
        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern("/pets/(id:number)"),
            securitySchemes = listOf(
                APIKeyInHeaderSecurityScheme("X-Api-Key", null),
                APIKeyInQueryParamSecurityScheme("api_key", null)
            )
        )

        val negativePatterns = requestPattern.negativeBasedOn(
            row = Row(mapOf("id" to "10")),
            resolver = Resolver()
        ).map { it.value }.toList()

        assertThat(negativePatterns).isNotEmpty
        assertThat(negativePatterns).allSatisfy { negativePattern ->
            assertThat(negativePattern.headersPattern.pattern).doesNotContainKey("X-Api-Key")
            assertThat(negativePattern.httpQueryParamPattern.queryPatterns).doesNotContainKey("api_key")
            assertThat(negativePattern.securitySchemes).contains(APIKeyInHeaderSecurityScheme("X-Api-Key", null))
        }
    }

    @Test
    fun `generation without examples should retain every top-level security alternative including composites`() {
        val headerScheme = APIKeyInHeaderSecurityScheme("X-Access-Key", null)
        val compositeScheme = CompositeSecurityScheme(
            listOf(
                APIKeyInHeaderSecurityScheme("X-Session-Key", null),
                APIKeyInQueryParamSecurityScheme("session_key", null)
            )
        )
        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern("/products"),
            securitySchemes = listOf(headerScheme, compositeScheme)
        )

        val generatedPatterns = requestPattern.newBasedOn(Resolver()).toList()

        assertThat(generatedPatterns.map { it.securitySchemes.single() })
            .containsExactly(headerScheme, compositeScheme)
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

    @Nested
    inner class ResolveSubstitutionsTests {
        @Test
        fun `should resolve stored composite object and array values in request body`() {
            val profilePattern = JSONObjectPattern(
                typeAlias = "(Profile)",
                pattern = mapOf("name" to StringPattern()),
            )

            val petPattern = JSONObjectPattern(
                typeAlias = "(Pet)",
                pattern = mapOf("name" to StringPattern()),
            )

            val petsPattern = ListPattern(petPattern, typeAlias = "(Pets)")
            val bodyPattern = JSONObjectPattern(pattern = mapOf("profile" to profilePattern, "pets" to petsPattern))
            val resolver = Resolver(newPatterns = mapOf("(Profile)" to profilePattern, "(Pet)" to petPattern, "(Pets)" to petsPattern))

            val profile = parsedJSONObject("""{"name": "Sherlock"}""")
            val pets = parsedJSONArray("""[{"name": "Dog"},{"name": "Cat"}]""")
            val substitution = SubstitutionImpl.empty()
                .upsertStoreUsing(StringValue("(profile:Profile)"), StringValue(profile.toUnformattedString()), resolver)
                .upsertStoreUsing(StringValue("(pets:Pets)"), StringValue(pets.toUnformattedString()), resolver)

            val requestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/pets"),
                body = bodyPattern,
            )
            val request = HttpRequest(
                method = "POST",
                path = "/pets",
                body = parsedJSONObject("""{"profile": "$(profile)", "pets": "$(pets)"}"""),
            )

            val resolved = requestPattern.resolveSubstitutions(substitution, request, resolver).value
            assertThat(resolved.body).isEqualTo(
                parsedJSONObject("""{"profile": {"name": "Sherlock"}, "pets": [{"name": "Dog"}, {"name": "Cat"}]}""")
            )
        }

        @Test
        fun `should resolve composite object and array values from data lookup in request body`() {
            val profilePattern = JSONObjectPattern(
                typeAlias = "(Profile)",
                pattern = mapOf("name" to StringPattern()),
            )

            val petPattern = JSONObjectPattern(
                typeAlias = "(Pet)",
                pattern = mapOf("name" to StringPattern()),
            )

            val petsPattern = ListPattern(petPattern, typeAlias = "(Pets)")
            val bodyPattern = JSONObjectPattern(pattern = mapOf("profile" to profilePattern, "pets" to petsPattern))
            val resolver = Resolver(newPatterns = mapOf("(Profile)" to profilePattern, "(Pet)" to petPattern, "(Pets)" to petsPattern))

            val substitution = SubstitutionImpl.from(
                resolver = resolver,
                runningRequest = HttpRequest(method = "POST", path = "/profiles/10"),
                originalRequest = HttpRequest(method = "POST", path = "/profiles/(ID:number)"),
                data = parsedJSONObject("""
                {
                  "lookupData": {
                    "dictionary": {
                      "10": {
                        "profile": {"name": "Sherlock"},
                        "pets": [{"name": "Dog"}, {"name": "Cat"}]
                      }
                    }
                  }
                }
                """.trimIndent())
            )

            val requestPattern = HttpRequestPattern(
                method = "POST",
                httpPathPattern = buildHttpPathPattern("/profiles/(ID:number)"),
                body = bodyPattern,
            )

            val request = HttpRequest(
                method = "POST",
                path = "/profiles/10",
                body = parsedJSONObject(
                    """{"profile": "$(lookupData.dictionary[ID].profile)", "pets": "$(lookupData.dictionary[ID].pets)"}"""
                ),
            )

            val resolved = requestPattern.resolveSubstitutions(substitution, request, resolver).value
            assertThat(resolved.body).isEqualTo(
                parsedJSONObject("""{"profile": {"name": "Sherlock"}, "pets": [{"name": "Dog"}, {"name": "Cat"}]}""")
            )
        }

        @Test
        fun `should resolve path query and header and body values`() {
            val requestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/pets/(id:string)"),
                body = JSONObjectPattern(mapOf("name" to StringPattern())),
                headersPattern = HttpHeadersPattern(mapOf("X-Trace" to StringPattern())),
                httpQueryParamPattern = HttpQueryParamPattern(mapOf("page" to QueryParameterScalarPattern(StringPattern()))),
            )

            val request = HttpRequest(
                method = "GET",
                path = "/pets/$(id)",
                headers = mapOf("X-Trace" to "$(trace)"),
                body = parsedJSONObject("""{"name": "$(name)"}"""),
                queryParams = QueryParameters(mapOf("page" to "$(page)")),
            )

            val substitution = substitutionOf(
                "id" to NumberValue(123),
                "page" to NumberValue(2),
                "name" to StringValue("John"),
                "trace" to StringValue("abc"),
            )

            val resolved = requestPattern.resolveSubstitutions(substitution, request, Resolver()).value
            assertThat(resolved.path).isEqualTo("/pets/123")
            assertThat(resolved.queryParams.asMap()).containsEntry("page", "2")
            assertThat(resolved.headers).containsEntry("X-Trace", "abc")
            assertThat(resolved.body.toUnformattedString()).isEqualTo("""{"name":"John"}""")
        }

        @Test
        fun `should resolve security scheme values too`() {
            val requestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/pets/(id:string)"),
                httpQueryParamPattern = HttpQueryParamPattern(mapOf("page" to QueryParameterScalarPattern(StringPattern()))),
                headersPattern = HttpHeadersPattern(mapOf("X-Trace" to StringPattern())),
                securitySchemes = listOf(
                    APIKeyInHeaderSecurityScheme("X-Api-Key", null),
                    APIKeyInQueryParamSecurityScheme("api_key", null)
                )
            )

            val request = HttpRequest(
                method = "GET",
                path = "/pets/$(id)",
                headers = mapOf("X-Trace" to "$(trace)", "X-Api-Key" to "$(header_key)"),
                queryParams = QueryParameters(mapOf("page" to "$(page)", "api_key" to "$(api_key)")),
            )

            val substitution = substitutionOf(
                "id" to NumberValue(123),
                "page" to NumberValue(2),
                "trace" to StringValue("abc"),
                "api_key" to StringValue("secret"),
                "header_key" to StringValue("header-secret")
            )

            val resolved = requestPattern.resolveSubstitutions(substitution, request, Resolver()).value
            assertThat(resolved.path).isEqualTo("/pets/123")
            assertThat(resolved.queryParams.asMap()).containsEntry("page", "2")
            assertThat(resolved.queryParams.asMap()).containsEntry("api_key", "secret")
            assertThat(resolved.headers).containsEntry("X-Trace", "abc")
            assertThat(resolved.headers).containsEntry("X-Api-Key", "header-secret")
        }

        @Test
        fun `should use dictionary backed generation when substitutions are unresolved across request`() {
            val addressPattern = JSONObjectPattern(
                typeAlias = "(Address)",
                pattern = mapOf("street" to StringPattern()),
            )

            val bodyPattern = JSONObjectPattern(
                typeAlias = "(Pet)",
                pattern = mapOf("name" to StringPattern(), "address" to addressPattern),
            )

            val requestPattern = HttpRequestPattern(
                body = bodyPattern,
                httpPathPattern = buildHttpPathPattern("/pets/(id:number)"),
                headersPattern = HttpHeadersPattern(mapOf("X-Trace" to StringPattern())),
                httpQueryParamPattern = HttpQueryParamPattern(mapOf("page" to QueryParameterScalarPattern(NumberPattern()))),
            )

            val request = HttpRequest(
                method = "GET",
                path = "/pets/$(missing-id)",
                headers = mapOf("X-Trace" to "$(missing-trace)"),
                queryParams = QueryParameters(mapOf("page" to "$(missing-page)")),
                body = parsedJSONObject("""{"name": "$(missing-name)", "address": {"street": "$(missing-street)"}}"""),
            )

            val resolver = Resolver(
                newPatterns = mapOf("(Pet)" to bodyPattern, "(Address)" to addressPattern),
                dictionary = Dictionary.fromYaml("""
                PARAMETERS:
                  PATH:
                    id: 123
                  QUERY:
                    page: 7
                  HEADER:
                    X-Trace: trace-from-dictionary
                Pet:
                  name: Fido
                Address:
                  street: Baker Street
                """.trimIndent())
            )

            val resolved = requestPattern.resolveSubstitutions(SubstitutionImpl.empty(), request, resolver).value
            assertThat(resolved.path).isEqualTo("/pets/123")
            assertThat(resolved.queryParams.asMap()).containsEntry("page", "7")
            assertThat(resolved.headers).containsEntry("X-Trace", "trace-from-dictionary")
            assertThat(resolved.body).isEqualTo(
                parsedJSONObject("""{"name": "Fido", "address": {"street": "Baker Street"}}""")
            )
        }

        private fun substitutionOf(vararg mappings: Pair<String, Value>): Substitution {
            return mappings.fold(SubstitutionImpl.empty()) { acc, (key, value) ->
                acc.upsertStoreUsing(StringValue("($key:${value.type().typeName})"), value)
            }
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
        val newRequestPattern = httpRequestPattern.generateExactHttpRequestPatternFrom(httpRequest, Resolver())

        assertThat(newRequestPattern.httpPathPattern).isEqualTo(
            HttpPathPattern(
                path = "/invalidUUID",
                pathSegmentPatterns = listOf(
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/"))),
                    URLPathSegmentPattern(ExactValuePattern(StringValue("invalidUUID")), "id"),
                ),
            )
        )
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
        val newRequestPattern = httpRequestPattern.generateExactHttpRequestPatternFrom(httpRequest, Resolver())

        assertThat(newRequestPattern.body).isEqualTo(EmptyStringPattern)
    }

    private fun String.toExactValuePattern(): ExactValuePattern = ExactValuePattern(StringValue(this))

    companion object {
        @JvmStatic
        fun headersBasedSecuritySchemesProvider(): Stream<OpenAPISecurityScheme> = Stream.of(
            APIKeyInHeaderSecurityScheme(AUTHORIZATION, "1234"),
            BasicAuthSecurityScheme("1234"),
            BearerSecurityScheme("1234"),
        )
    }
}
