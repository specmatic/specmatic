package io.specmatic.core

import io.mockk.every
import io.mockk.mockk
import io.specmatic.GENERATION
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.*
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URISyntaxException

class HttpQueryParamPatternTest {
    @Test
    fun `request url query params should not match a url with unknown query params`() {
        val matcher = buildQueryPattern(URI("/pets?id=(string)"))
        assertThat(matcher.matches(URI("/pets"), mapOf("name" to "Jack Daniel"))).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `should match a boolean in a query only when resolver has mock matching on`() {
        val matcher = buildQueryPattern(URI("/pets?available=(boolean)"))
        assertThat(matcher.matches(URI.create("/pets"), mapOf("available" to "true"), Resolver())).isInstanceOf(Success::class.java)
        assertThat(matcher.matches(URI.create("/pets"), mapOf("available" to "(boolean)"), Resolver(mockMode = true))).isInstanceOf(
            Success::class.java)
        assertThat(matcher.matches(URI.create("/pets"), mapOf("available" to "(boolean)"), Resolver(mockMode = false))).isInstanceOf(
            Failure::class.java)
    }

    @Test
    fun `url matcher with a mandatory query param should not match empty query params`() {
        val matcher = HttpQueryParamPattern(mapOf("name" to StringPattern()))
        val result = matcher.matches(URI("/"), emptyMap(), Resolver())
        assertThat(result.isSuccess()).isFalse()
    }

    @Test
    fun `should match a number in a query only when resolver has mock matching on`() {
        val matcher = buildQueryPattern(URI("/pets?id=(number)"))
        assertThat(matcher.matches(URI.create("/pets"), mapOf("id" to "10"), Resolver())).isInstanceOf(Success::class.java)
        assertThat(matcher.matches(URI.create("/pets"), mapOf("id" to "(number)"), Resolver(mockMode = true))).isInstanceOf(
            Success::class.java)
        assertThat(matcher.matches(URI.create("/pets"), mapOf("id" to "(number)"), Resolver(mockMode = false))).isInstanceOf(
            Failure::class.java)
    }

    @Test
    @Throws(URISyntaxException::class, UnsupportedEncodingException::class)
    fun `should not match url when query parameters do not match`() {
        val urlPattern = buildQueryPattern(URI("/pets?petid=(number)"))
        val queryParameters = mapOf("petid" to "text")

        urlPattern.matches(URI("/pets"), queryParameters, Resolver()).let {
            assertThat(it is Failure).isTrue()
            assertThat((it as Failure).toMatchFailureDetails()).isEqualTo(
                MatchFailureDetails(listOf(BreadCrumb.PARAM_QUERY.value, "petid"), listOf(DefaultMismatchMessages.typeMismatch("number", "\"text\"", "string")))
            )
        }
    }

    @Test
    @Throws(URISyntaxException::class, UnsupportedEncodingException::class)
    fun `should match url with only query parameters`() {
        val urlPattern = buildQueryPattern(URI("/pets?petid=(number)&owner=(string)"))
        val queryParameters = hashMapOf(
            "petid" to "123123",
            "owner" to "hari"
        )
        urlPattern.matches(URI("/pets"), queryParameters, Resolver()).let {
            assertThat(it is Success).isTrue()
        }
    }

    @Test
    fun `request url with 1 query param should match a url pattern with superset of 2 params`() {
        val matcher = buildQueryPattern(URI("/pets?id=(string)&name=(string)"))
        assertThat(
            matcher.matches(
                URI("/pets"),
                mapOf("name" to "Jack Daniel")
            )
        ).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should match nested object query params using inferred dot property syntax`() {
        val matcher = nestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )

        val result = matcher.matches(
            HttpRequest(
                "GET",
                "/people",
                queryParams = QueryParameters(
                    listOf(
                        "name" to "Jack",
                        "address[0].street" to "Baker Street",
                        "address[0].city" to "London"
                    )
                )
            ),
            Resolver()
        )

        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should match nested object query params using inferred bracket property syntax`() {
        val matcher = nestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
        )

        val result = matcher.matches(
            HttpRequest(
                "GET",
                "/people",
                queryParams = QueryParameters(
                    listOf(
                        "name" to "Jack",
                        "address[0][street]" to "Baker Street",
                        "address[0][city]" to "London"
                    )
                )
            ),
            Resolver()
        )

        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should match parameter-wrapped nested object query params using inferred dot property syntax`() {
        val matcher = nestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )

        val result = matcher.matches(
            HttpRequest(
                "GET",
                "/people",
                queryParams = QueryParameters(
                    listOf(
                        "details[name]" to "Jack",
                        "details[address][0].street" to "Baker Street",
                        "details[address][0].city" to "London"
                    )
                )
            ),
            Resolver()
        )

        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should match parameter-wrapped nested object query params using inferred bracket property syntax`() {
        val matcher = nestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
        )

        val result = matcher.matches(
            HttpRequest(
                "GET",
                "/people",
                queryParams = QueryParameters(
                    listOf(
                        "details[name]" to "Jack",
                        "details[address][0][street]" to "Baker Street",
                        "details[address][0][city]" to "London"
                    )
                )
            ),
            Resolver()
        )

        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should reject nested object query params using syntax different from inferred syntax`() {
        val matcher = nestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )

        val result = matcher.matches(
            HttpRequest(
                "GET",
                "/people",
                queryParams = QueryParameters(
                    listOf(
                        "name" to "Jack",
                        "address[0][street]" to "Baker Street",
                        "address[0][city]" to "London"
                    )
                )
            ),
            Resolver()
        )

        assertThat(result).isInstanceOf(Failure::class.java)
        assertThat((result as Failure).reportString()).contains("does not match inferred dot property syntax")
    }

    @Test
    fun `should reject malformed nested object query keys at runtime`() {
        val matcher = nestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )

        val result = matcher.matches(
            HttpRequest(
                "GET",
                "/people",
                queryParams = QueryParameters(
                    listOf(
                        "name" to "Jack",
                        "address[0].street" to "Baker Street",
                        "address[0].city." to "London"
                    )
                )
            ),
            Resolver()
        )

        assertThat(result).isInstanceOf(Failure::class.java)
        assertThat((result as Failure).reportString()).contains("Empty dot token")
    }

    @Test
    fun `should explain missing required properties in nested object query params`() {
        val matcher = nestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )

        val result = matcher.matches(
            HttpRequest(
                "GET",
                "/people",
                queryParams = QueryParameters(
                    listOf(
                        "name" to "Jack",
                        "address[0].street" to "Baker Street"
                    )
                )
            ),
            Resolver()
        )

        assertThat(result).isInstanceOf(Failure::class.java)
        assertThat((result as Failure).reportString()).contains("details", "address", "city")
    }

    @Test
    fun `should treat numeric-looking nested object query tokens as properties when schema position is object`() {
        val matcher = HttpQueryParamPattern(
            queryPatterns = mapOf(
                "details" to JSONObjectPattern(
                    mapOf(
                        "address" to JSONObjectPattern(
                            mapOf(
                                "0" to JSONObjectPattern(
                                    mapOf("street" to StringPattern())
                                )
                            )
                        )
                    )
                )
            ),
            nestedObjectQueryParams = listOf(
                NestedObjectQueryParam(
                    parameterName = "details",
                    required = true,
                    schema = NestedQuerySchema.Object(
                        properties = mapOf(
                            "address" to NestedQuerySchema.Object(
                                properties = mapOf(
                                    "0" to NestedQuerySchema.Object(
                                        properties = mapOf("street" to NestedQuerySchema.Scalar)
                                    )
                                )
                            )
                        )
                    ),
                    syntax = ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
                )
            )
        )

        val result = matcher.matches(
            HttpRequest(
                "GET",
                "/people",
                queryParams = QueryParameters(listOf("address.0.street" to "Baker Street"))
            ),
            Resolver()
        )

        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should parse nested scalar query leaves before matching reconstructed object`() {
        val matcher = ecommerceNestedFilterQueryParamPattern()

        val result = matcher.matches(
            HttpRequest(
                "GET",
                "/products/search",
                queryParams = QueryParameters(
                    listOf(
                        "category" to "shoes",
                        "available" to "true",
                        "price.min" to "50",
                        "price.max" to "150",
                        "price.currency" to "USD",
                        "variants[0].color" to "black",
                        "variants[0].sizes[0]" to "9",
                        "variants[0].sizes[1]" to "10"
                    )
                )
            ),
            Resolver()
        )

        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should report reconstructed nested object path when nested scalar query leaf cannot be parsed`() {
        val matcher = ecommerceNestedFilterQueryParamPattern()

        val result = matcher.matches(
            HttpRequest(
                "GET",
                "/products/search",
                queryParams = QueryParameters(
                    listOf(
                        "category" to "shoes",
                        "available" to "true",
                        "price.min" to "cheap",
                        "price.max" to "150",
                        "price.currency" to "USD",
                        "variants[0].color" to "black",
                        "variants[0].sizes[0]" to "9"
                    )
                )
            ),
            Resolver()
        )

        assertThat(result).isInstanceOf(Failure::class.java)
        assertThat((result as Failure).reportString()).contains("filter.price.min", "number")
    }

    @Test
    fun `should fix nested object query params and serialize using inferred syntax`() {
        val queryPattern = nestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )
        val invalidValue = QueryParameters(
            listOf(
                "name" to "Jack",
                "address[0].street" to "Baker Street"
            )
        )
        val dictionary = """
            PARAMETERS:
              QUERY:
                details:
                  address:
                    - city: London
        """.trimIndent().let(Dictionary::fromYaml)

        val fixedValue = queryPattern.fixValue(invalidValue, Resolver(dictionary = dictionary))

        assertThat(fixedValue.paramPairs).contains(
            "name" to "Jack",
            "address[0].street" to "Baker Street",
            "address[0].city" to "London"
        )
    }

    @Test
    fun `should fill nested object query params from dictionary and serialize using inferred syntax`() {
        val queryPattern = nestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
        )
        val dictionary = """
            PARAMETERS:
              QUERY:
                details:
                  name: Jack
                  address:
                    - street: Baker Street
                      city: London
        """.trimIndent().let(Dictionary::fromYaml)

        val filledValue = queryPattern.fillInTheBlanks(null, Resolver(dictionary = dictionary)).value

        assertThat(filledValue.paramPairs).containsExactlyInAnyOrder(
            "name" to "Jack",
            "address[0][street]" to "Baker Street",
            "address[0][city]" to "London"
        )
    }

    @Test
    @Tag(GENERATION)
    fun `should generate nested object query params using every supported inferred syntax`() {
        val generationCases = listOf(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket) to listOf(
                "name" to "Jack",
                "address[0][street]" to "Baker Street",
                "address[0][city]" to "London"
            ),
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket) to listOf(
                "name" to "Jack",
                "address[0].street" to "Baker Street",
                "address[0].city" to "London"
            ),
            ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket) to listOf(
                "details[name]" to "Jack",
                "details[address][0].street" to "Baker Street",
                "details[address][0].city" to "London"
            ),
            ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket) to listOf(
                "details[name]" to "Jack",
                "details[address][0][street]" to "Baker Street",
                "details[address][0][city]" to "London"
            )
        )

        generationCases.forEach { (syntax, expectedQueryParams) ->
            val queryPattern = deterministicNestedDetailsQueryParamPattern(syntax)
            val generatedValue = queryPattern.generate(Resolver())

            assertThat(generatedValue).containsAll(expectedQueryParams)
            assertThat(generatedValue.map { it.first }).doesNotContain("details")
        }
    }

    @Test
    @Tag(GENERATION)
    fun `should generate nested object query params from newBasedOn using inferred syntax`() {
        val queryPattern = deterministicNestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
        )

        val generatedPattern = queryPattern.newBasedOn(Row(), Resolver()).first { it.value.queryPatterns.containsKey("details") }.value
        val generatedValue = generatedPattern.generate(Resolver())

        assertThat(generatedValue).contains(
            "details[name]" to "Jack",
            "details[address][0][street]" to "Baker Street",
            "details[address][0][city]" to "London"
        )
        assertThat(generatedValue.map { it.first }).doesNotContain("details")
    }

    @Test
    @Tag(GENERATION)
    fun `should generate nested object query params from newBasedOn row values using inferred syntax`() {
        val queryPattern = nestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )
        val row = nestedDetailsRow()

        val generatedPattern = queryPattern.newBasedOn(row, Resolver()).single().value
        val generatedValue = generatedPattern.generate(Resolver())

        assertThat(generatedValue).contains(
            "name" to "Row Jack",
            "address[0].street" to "Row Street",
            "address[0].city" to "Row City"
        )
        assertThat(generatedValue.map { it.first }).allSatisfy { key ->
            assertThat(key).matches("name|address\\[[0-9]+]\\.street|address\\[[0-9]+]\\.city")
        }
    }

    @Test
    @Tag(GENERATION)
    fun `should generate negative nested object query params from row values using inferred syntax`() {
        val queryPattern = nestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )
        val row = nestedDetailsRow()

        val generatedPattern = queryPattern.negativeBasedOn(row, Resolver()).first { it.value.queryPatterns.containsKey("details") }.value
        val generatedValue = generatedPattern.generate(Resolver())

        assertThat(generatedValue.map { it.first }).allSatisfy { key ->
            assertThat(key).matches("details\\[name]|details\\[address]\\[[0-9]+]\\.street|details\\[address]\\[[0-9]+]\\.city")
        }
        assertThat(generatedValue.map { it.first }).doesNotContain("details")
    }

    @Test
    @Tag(GENERATION)
    fun `should generate negative nested object query params using inferred syntax`() {
        val queryPattern = deterministicNestedDetailsQueryParamPattern(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )

        val generatedPattern = queryPattern.negativeBasedOn(Row(), Resolver()).first { it.value.queryPatterns.containsKey("details") }.value
        val generatedValue = generatedPattern.generate(Resolver())

        assertThat(generatedValue.map { it.first }).allSatisfy { key ->
            assertThat(key).matches("name|address\\[[0-9]+]\\.street|address\\[[0-9]+]\\.city")
        }
        assertThat(generatedValue).isNotEmpty
    }

    private fun ecommerceNestedFilterQueryParamPattern(): HttpQueryParamPattern {
        return HttpQueryParamPattern(
            queryPatterns = mapOf(
                "filter" to JSONObjectPattern(
                    mapOf(
                        "category" to StringPattern(),
                        "available" to BooleanPattern(),
                        "price" to JSONObjectPattern(
                            mapOf(
                                "min" to NumberPattern(),
                                "max" to NumberPattern(),
                                "currency" to StringPattern()
                            )
                        ),
                        "variants" to ListPattern(
                            JSONObjectPattern(
                                mapOf(
                                    "color" to StringPattern(),
                                    "sizes" to ListPattern(NumberPattern())
                                )
                            )
                        )
                    )
                )
            ),
            nestedObjectQueryParams = listOf(
                NestedObjectQueryParam(
                    parameterName = "filter",
                    required = true,
                    schema = NestedQuerySchema.Object(
                        properties = mapOf(
                            "category" to NestedQuerySchema.Scalar,
                            "available" to NestedQuerySchema.Scalar,
                            "price" to NestedQuerySchema.Object(
                                properties = mapOf(
                                    "min" to NestedQuerySchema.Scalar,
                                    "max" to NestedQuerySchema.Scalar,
                                    "currency" to NestedQuerySchema.Scalar
                                )
                            ),
                            "variants" to NestedQuerySchema.Array(
                                itemSchema = NestedQuerySchema.Object(
                                    properties = mapOf(
                                        "color" to NestedQuerySchema.Scalar,
                                        "sizes" to NestedQuerySchema.Array(NestedQuerySchema.Scalar)
                                    )
                                )
                            )
                        )
                    ),
                    syntax = ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
                )
            )
        )
    }

    private fun nestedDetailsRow(): Row {
        val requestExample = parsedJSONObject(
            """
            {
              "details": {
                "name": "Row Jack",
                "address": [
                  {
                    "street": "Row Street",
                    "city": "Row City"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        return Row(requestBodyJSONExample = JSONExample(requestExample, Row()))
    }

    private fun deterministicNestedDetailsQueryParamPattern(syntax: ObjectQuerySyntax): HttpQueryParamPattern {
        return HttpQueryParamPattern(
            queryPatterns = mapOf(
                "details" to JSONObjectPattern(
                    mapOf(
                        "name" to ExactValuePattern(StringValue("Jack")),
                        "address" to ListPattern(
                            JSONObjectPattern(
                                mapOf(
                                    "street" to ExactValuePattern(StringValue("Baker Street")),
                                    "city" to ExactValuePattern(StringValue("London"))
                                )
                            )
                        )
                    )
                )
            ),
            nestedObjectQueryParams = listOf(
                NestedObjectQueryParam(
                    parameterName = "details",
                    required = true,
                    schema = NestedQuerySchema.Object(
                        properties = mapOf(
                            "name" to NestedQuerySchema.Scalar,
                            "address" to NestedQuerySchema.Array(
                                itemSchema = NestedQuerySchema.Object(
                                    properties = mapOf(
                                        "street" to NestedQuerySchema.Scalar,
                                        "city" to NestedQuerySchema.Scalar
                                    )
                                )
                            )
                        )
                    ),
                    syntax = syntax
                )
            )
        )
    }

    private fun nestedDetailsQueryParamPattern(syntax: ObjectQuerySyntax): HttpQueryParamPattern {
        return HttpQueryParamPattern(
            queryPatterns = mapOf(
                "details" to JSONObjectPattern(
                    mapOf(
                        "name" to StringPattern(),
                        "address" to ListPattern(
                            JSONObjectPattern(
                                mapOf(
                                    "street" to StringPattern(),
                                    "city" to StringPattern()
                                )
                            )
                        )
                    )
                )
            ),
            nestedObjectQueryParams = listOf(
                NestedObjectQueryParam(
                    parameterName = "details",
                    required = true,
                    schema = NestedQuerySchema.Object(
                        properties = mapOf(
                            "name" to NestedQuerySchema.Scalar,
                            "address" to NestedQuerySchema.Array(
                                itemSchema = NestedQuerySchema.Object(
                                    properties = mapOf(
                                        "street" to NestedQuerySchema.Scalar,
                                        "city" to NestedQuerySchema.Scalar
                                    )
                                )
                            )
                        )
                    ),
                    syntax = syntax
                )
            )
        )
    }

    @Test
    fun `should generate query`() {
        val urlPattern = buildQueryPattern(URI("/pets?petid=(number)&owner=(string)"))
        val resolver = mockk<Resolver>().also {
            every {
                it.updateLookupPath(any(), any())
            } returns it
            every {
                it.updateLookupForParam("QUERY")
            } returns it
            every {
                it.withCyclePrevention<StringValue>(
                    QueryParameterScalarPattern(ExactValuePattern(StringValue("pets"))),
                    any()
                )
            } returns StringValue("pets")
            every {
                it.withCyclePrevention<NumberValue>(
                    QueryParameterScalarPattern(DeferredPattern("(number)", "petid")),
                    any()
                )
            } returns NumberValue(123)
            every {
                it.withCyclePrevention<StringValue>(
                    QueryParameterScalarPattern(DeferredPattern("(string)", "owner")),
                    any()
                )
            } returns StringValue("hari")
        }
        urlPattern.generate(resolver).let {
            assertThat(it).isEqualTo(listOf("owner" to "hari", "petid" to "123"))
        }
    }

    @Test
    @Tag(GENERATION)
    fun `should generate a valid query string when there is a single row with matching columns`() {
        val resolver = Resolver()
        val row = Row(listOf("status", "type"), listOf("available", "dog"))
        val generatedPatterns = buildQueryPattern(URI("/pets?status=(string)&type=(string)")).newBasedOn(row, resolver).toList()
        assertEquals(1, generatedPatterns.size)
        val values = generatedPatterns.first().value.generate(resolver)
        assertThat(values.single{ it.first == "status"}.second).isEqualTo("available")
        assertThat(values.single{ it.first == "type"}.second).isEqualTo("dog")
    }

    @Test
    fun `given a pattern in a query param, it should generate a random value matching that pattern`() {
        val matcher = buildQueryPattern(URI("/pets?id=(string)"))
        val query = matcher.generate(Resolver())

        Assertions.assertNotEquals("(string)", query.single{ it.first == "id"}.second)
        assertTrue(query.single{ it.first == "id"}.second.isNotEmpty())
    }

    @Test
    fun `url matcher with 2 non optional query params should not match a url with just one of the specified query params`() {
        val matcher =
            HttpQueryParamPattern(queryPatterns = mapOf("name" to StringPattern(), "string" to StringPattern()))

        val result = matcher.matches(HttpRequest(queryParametersMap = mapOf("name" to "Archie")), Resolver())
        assertThat(result.isSuccess()).isFalse()
    }

    @Test
    fun `should stringify date time query param to date time pattern`() {
        val httpQueryParamPattern = HttpQueryParamPattern(mapOf("before" to DateTimePattern))
        assertThat(httpQueryParamPattern.toString()).isEqualTo("?before=(datetime)")
    }

    @Test
    @Tag(GENERATION)
    fun `should generate a path with a concrete value given a query param with newBasedOn`() {
        val matcher = buildQueryPattern(URI("/pets?available=(boolean)"))
        val matchers = matcher.newBasedOn(Row(), Resolver()).toList().map { it.value.queryPatterns }
        assertThat(matchers).hasSize(2)
        assertThat(matchers).contains(emptyMap())
        assertThat(matchers).contains(mapOf("available" to BooleanPattern()))
    }

    @Test
    @Throws(URISyntaxException::class, UnsupportedEncodingException::class)
    fun `should match url with both path and query parameters`() {
        val urlPattern = buildQueryPattern(URI("/pets/(petid:number)?owner=(string)"))
        val queryParameters = hashMapOf("owner" to "Hari")
        urlPattern.matches(URI("/pets/123123"), queryParameters, Resolver()).let {
            assertThat(it is Success).isTrue()
        }
    }

    @Tag(GENERATION)
    @Test
    fun `should generate negative values for a string`() {
        val urlMatchers =
            buildQueryPattern(URI("/pets?name=(string)")).negativeBasedOn(Row(), Resolver()).toList().map { it.value.queryPatterns }
        assertThat(urlMatchers).containsExactly(emptyMap())
    }

    @Test
    @Tag(GENERATION)
    fun `should not create 2^n matchers on an empty Row`() {
        val patterns = buildQueryPattern(URI("/pets?status=(string)&type=(string)"))
        val generatedPatterns = patterns.newBasedOn(Row(), Resolver()).toList().map { it.value.queryPatterns }
        assertThat(generatedPatterns).containsExactlyInAnyOrder(
            emptyMap(),
            mapOf("status" to StringPattern(), "type" to StringPattern()),
        )
    }

    @Test
    @Tag(GENERATION)
    fun `should generate absent required-only and all combinations for optional form exploded object query params from row`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "name?" to QueryParameterScalarPattern(StringPattern()),
                "description?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = false,
                    propertyKeys = setOf("name", "description"),
                    requiredPropertyKeys = setOf("name")
                )
            )
        )

        val generatedPatterns = queryPattern.newBasedOn(Row(), Resolver()).toList().map { it.value.queryPatterns.keys }

        assertThat(generatedPatterns).containsExactlyInAnyOrder(
            emptySet(),
            setOf("name"),
            setOf("name", "description")
        )
    }

    @Test
    @Tag(GENERATION)
    fun `should generate absent required-only and all combinations for optional form exploded object query params without row`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "name?" to QueryParameterScalarPattern(StringPattern()),
                "description?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = false,
                    propertyKeys = setOf("name", "description"),
                    requiredPropertyKeys = setOf("name")
                )
            )
        )

        val generatedPatterns = queryPattern.newBasedOn(Resolver()).toList().map { it.queryPatterns.keys }

        assertThat(generatedPatterns).containsExactlyInAnyOrder(
            emptySet(),
            setOf("name"),
            setOf("name", "description")
        )
    }

    @Test
    fun `should explain missing required properties when optional form exploded object query param is partially present`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "name?" to QueryParameterScalarPattern(StringPattern()),
                "description?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = false,
                    propertyKeys = setOf("name", "description"),
                    requiredPropertyKeys = setOf("name")
                )
            )
        )

        val result = queryPattern.matches(
            HttpRequest("GET", "/", queryParams = QueryParameters(listOf("description" to "buyer"))),
            Resolver()
        )

        assertThat(result).isInstanceOf(Failure::class.java)
        assertThat(result.reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "PARAMETERS.QUERY.name",
                details = "The request includes property \"description\" from optional form-exploded query parameter object \"info\". Since that object is present, required property \"name\" must also be provided.",
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        )
    }

    @Test
    fun `should explain missing required properties when mandatory form exploded object query param has only optional property`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "name" to QueryParameterScalarPattern(StringPattern()),
                "description?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = true,
                    propertyKeys = setOf("name", "description"),
                    requiredPropertyKeys = setOf("name")
                )
            )
        )

        val result = queryPattern.matches(
            HttpRequest("GET", "/", queryParams = QueryParameters(listOf("description" to "buyer"))),
            Resolver()
        )

        assertThat(result).isInstanceOf(Failure::class.java)
        assertThat(result.reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "PARAMETERS.QUERY.name",
                details = "The request includes property \"description\" from required form-exploded query parameter object \"info\". Required property \"name\" must also be provided.",
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        )
    }

    @Test
    fun `colliding query key should match using authoritative scalar owner without activating object query parameter`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "age?" to QueryParameterScalarPattern(StringPattern()),
                "name?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = false,
                    propertyKeys = setOf("age", "name"),
                    requiredPropertyKeys = setOf("name")
                )
            ),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        QueryParameterCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            required = false,
                            parameterName = "age"
                        ),
                        QueryParameterCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            required = false,
                            parameterName = "info",
                            propertyName = "age"
                        )
                    ),
                    authoritativeOwner = QueryParameterCollisionOwner(
                        wireKey = "age",
                        sourceName = "age",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        required = false,
                        parameterName = "age"
                    )
                )
            )
        )

        assertThat(
            queryPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(listOf("age" to "10"))), Resolver())
        ).isInstanceOf(Success::class.java)
        val scalarMismatchResult = queryPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(listOf("age" to "abc"))), Resolver())
        assertThat(scalarMismatchResult).isInstanceOf(Failure::class.java)
        assertThat((scalarMismatchResult as Failure).reportString()).contains("PARAMETERS.QUERY.age")
        assertThat(scalarMismatchResult.reportString()).contains("number")
    }

    @Test
    fun `colliding query key should match using authoritative object property and activate required sibling behavior`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "age?" to QueryParameterScalarPattern(StringPattern()),
                "name?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = false,
                    propertyKeys = setOf("age", "name"),
                    requiredPropertyKeys = setOf("name")
                )
            ),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
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
            )
        )

        val missingSiblingResult = queryPattern.matches(
            HttpRequest("GET", "/", queryParams = QueryParameters(listOf("age" to "10"))),
            Resolver()
        )

        assertThat(missingSiblingResult).isInstanceOf(Failure::class.java)
        assertThat(missingSiblingResult.reportString()).contains("required property \"name\" must also be provided")
        assertThat(
            queryPattern.matches(
                HttpRequest("GET", "/", queryParams = QueryParameters(listOf("age" to "10", "name" to "John"))),
                Resolver()
            )
        ).isInstanceOf(Success::class.java)
        val objectPropertyMismatchResult = queryPattern.matches(
            HttpRequest("GET", "/", queryParams = QueryParameters(listOf("age" to "abc", "name" to "John"))),
            Resolver()
        )
        assertThat(objectPropertyMismatchResult).isInstanceOf(Failure::class.java)
        assertThat((objectPropertyMismatchResult as Failure).reportString()).contains("PARAMETERS.QUERY.age")
        assertThat(objectPropertyMismatchResult.reportString()).contains("number")
    }

    @Test
    fun `colliding repeated query key should match using authoritative array owner`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("ids?" to QueryParameterScalarPattern(StringPattern())),
            collisionGroupsByWireKey = mapOf(
                "ids" to QueryParameterCollisionGroup(
                    wireKey = "ids",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "ids",
                            sourceName = "ids",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterArrayPattern(listOf(NumberPattern()), "ids")
                        ),
                        queryCollisionOwner(
                            wireKey = "ids",
                            sourceName = "filter.ids",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            parameterName = "filter",
                            propertyName = "ids"
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "ids",
                        sourceName = "ids",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterArrayPattern(listOf(NumberPattern()), "ids")
                    )
                )
            )
        )

        assertThat(
            queryPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(listOf("ids" to "1", "ids" to "2"))), Resolver())
        ).isInstanceOf(Success::class.java)
        val arrayMismatchResult = queryPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(listOf("ids" to "1", "ids" to "abc"))), Resolver())
        assertThat(arrayMismatchResult).isInstanceOf(Failure::class.java)
        assertThat((arrayMismatchResult as Failure).reportString()).contains("PARAMETERS.QUERY.ids")
        assertThat(arrayMismatchResult.reportString()).contains("number")
    }

    @Test
    fun `required authoritative scalar owner should make colliding key mandatory`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("age?" to QueryParameterScalarPattern(StringPattern())),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            required = true
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "age",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        required = true
                    )
                )
            )
        )

        assertThat(queryPattern.matches(HttpRequest("GET", "/"), Resolver())).isInstanceOf(Failure::class.java)
        assertThat(queryPattern.queryKeyNames).containsExactly("age")
    }

    @Test
    fun `required authoritative object property owner should make colliding key mandatory`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("age?" to QueryParameterScalarPattern(StringPattern())),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            required = true,
                            parameterName = "info",
                            propertyName = "age"
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(StringPattern())
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "info.age",
                        kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        required = true,
                        parameterName = "info",
                        propertyName = "age"
                    )
                )
            )
        )

        assertThat(queryPattern.matches(HttpRequest("GET", "/"), Resolver())).isInstanceOf(Failure::class.java)
        assertThat(queryPattern.queryKeyNames).containsExactly("age")
    }

    @Test
    fun `generation should use authoritative collision owner pattern and key optionality`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("age?" to QueryParameterScalarPattern(StringPattern())),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            required = true
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "age",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        required = true
                    )
                )
            )
        )

        val generatedQueryParams = queryPattern.generate(Resolver())
        val generatedPatternKeys = queryPattern.newBasedOn(Row(), Resolver()).toList().map { it.value.queryPatterns.keys }

        assertThat(generatedQueryParams.map { it.first }).containsExactly("age")
        assertThat(generatedQueryParams.map { it.second }).allSatisfy { assertThat(it.toIntOrNull()).isNotNull() }
        assertThat(generatedPatternKeys).containsExactly(setOf("age"))
    }

    @Test
    fun `fill in the blanks should generate missing value from authoritative collision owner`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("age?" to QueryParameterScalarPattern(StringPattern())),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            required = true
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "age",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        required = true
                    )
                )
            )
        )

        val filledQueryParams = queryPattern.fillInTheBlanks(QueryParameters(emptyMap()), Resolver()).value

        val filledAge = filledQueryParams.getValues("age").single()
        assertThat(filledAge.toIntOrNull()).isNotNull()
    }

    @Test
    fun `fix value should repair invalid value using authoritative collision owner`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("age?" to QueryParameterScalarPattern(StringPattern())),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            required = true
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "age",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        required = true
                    )
                )
            )
        )

        val fixedQueryParams = queryPattern.fixValue(QueryParameters(mapOf("age" to "abc")), Resolver())

        val fixedAge = fixedQueryParams.getValues("age").single()
        assertThat(fixedAge.toIntOrNull()).isNotNull()
    }

    @Test
    fun `read from row should complete required object sibling when authoritative object property is present`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "age?" to QueryParameterScalarPattern(StringPattern()),
                "name?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = false,
                    propertyKeys = setOf("age", "name"),
                    requiredPropertyKeys = setOf("name")
                )
            ),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(StringPattern())
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "info.age",
                        kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        parameterName = "info",
                        propertyName = "age"
                    )
                )
            )
        )

        val completedPattern = queryPattern.readFrom(Row(mapOf("age" to "10")), Resolver(), generateMandatoryEntryIfMissing = true).single().value

        assertThat(completedPattern.queryPatterns.keys).containsExactlyInAnyOrder("age", "name")
        assertThat(completedPattern.queryPatterns["age"]).isInstanceOf(ExactValuePattern::class.java)
    }

    @Test
    fun `http request generation should emit authoritative collision owner query value`() {
        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = HttpPathPattern(emptyList(), "/"),
            httpQueryParamPattern = HttpQueryParamPattern(
                mapOf("age?" to QueryParameterScalarPattern(StringPattern())),
                collisionGroupsByWireKey = mapOf(
                    "age" to QueryParameterCollisionGroup(
                        wireKey = "age",
                        owners = listOf(
                            queryCollisionOwner(
                                wireKey = "age",
                                sourceName = "age",
                                kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                                pattern = QueryParameterScalarPattern(NumberPattern()),
                                required = true
                            ),
                            queryCollisionOwner(
                                wireKey = "age",
                                sourceName = "info.age",
                                kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                                pattern = QueryParameterScalarPattern(StringPattern()),
                                parameterName = "info",
                                propertyName = "age"
                            )
                        ),
                        authoritativeOwner = queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            required = true
                        )
                    )
                )
            )
        )

        val generatedRequest = requestPattern.generate(Resolver())

        val generatedAge = generatedRequest.queryParams.getValues("age").single()
        assertThat(generatedAge.toIntOrNull()).isNotNull()
    }

    @Test
    fun `generation from row should preserve exact scalar-first authoritative collision value`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("age?" to QueryParameterScalarPattern(StringPattern())),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            required = true
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "age",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        required = true
                    )
                )
            )
        )

        val generatedQueryPattern = queryPattern.newBasedOn(Row(mapOf("age" to "10")), Resolver()).single().value

        assertThat(generatedQueryPattern.generate(Resolver())).containsExactly("age" to "10")
    }

    @Test
    fun `generation from row should preserve exact object-first authoritative collision value`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "age?" to QueryParameterScalarPattern(StringPattern()),
                "name?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = false,
                    propertyKeys = setOf("age", "name"),
                    requiredPropertyKeys = setOf("name")
                )
            ),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(StringPattern())
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "info.age",
                        kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        parameterName = "info",
                        propertyName = "age"
                    )
                )
            )
        )

        val generatedQueryPattern = queryPattern.readFrom(Row(mapOf("age" to "10")), Resolver(), generateMandatoryEntryIfMissing = true).single().value

        assertThat(generatedQueryPattern.generate(Resolver()).toMap()["age"]).isEqualTo("10")
    }

    @Test
    fun `generation should not reintroduce omitted optional scalar-first authoritative collision key`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "age?" to QueryParameterScalarPattern(StringPattern()),
                "name?" to QueryParameterScalarPattern(StringPattern())
            ),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(NumberPattern())
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "age",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterScalarPattern(NumberPattern())
                    )
                )
            )
        )

        val generatedQueryPattern = queryPattern.newBasedOn(Row(mapOf("name" to "Jack")), Resolver()).single().value

        assertThat(generatedQueryPattern.generate(Resolver())).containsExactly("name" to "Jack")
    }

    @Test
    fun `generation should not reintroduce omitted optional object-first authoritative collision key`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "age?" to QueryParameterScalarPattern(StringPattern()),
                "name?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = false,
                    propertyKeys = setOf("age", "name"),
                    requiredPropertyKeys = setOf("name")
                )
            ),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(StringPattern())
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "info.age",
                        kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        parameterName = "info",
                        propertyName = "age"
                    )
                )
            )
        )

        val generatedQueryPattern = queryPattern.newBasedOn(Row(mapOf("name" to "Jack")), Resolver()).single().value

        assertThat(generatedQueryPattern.generate(Resolver())).containsExactly("name" to "Jack")
    }

    @Test
    fun `string representation should use authoritative collision owner pattern`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("age?" to QueryParameterScalarPattern(StringPattern())),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            required = true
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "age",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        required = true
                    )
                )
            )
        )

        assertThat(queryPattern.toString()).isEqualTo("?age=QueryParameterScalarPattern(pattern=(number))")
    }

    @Test
    fun `generation should use authoritative array collision owner`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("ids?" to QueryParameterScalarPattern(StringPattern())),
            collisionGroupsByWireKey = mapOf(
                "ids" to QueryParameterCollisionGroup(
                    wireKey = "ids",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "ids",
                            sourceName = "ids",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterArrayPattern(listOf(NumberPattern()), "ids"),
                            required = true
                        ),
                        queryCollisionOwner(
                            wireKey = "ids",
                            sourceName = "filter.ids",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            parameterName = "filter",
                            propertyName = "ids"
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "ids",
                        sourceName = "ids",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterArrayPattern(listOf(NumberPattern()), "ids"),
                        required = true
                    )
                )
            )
        )

        val generatedValues = queryPattern.generate(Resolver())

        assertThat(generatedValues).isNotEmpty
        assertThat(generatedValues.map { it.first }).allSatisfy { assertThat(it).isEqualTo("ids") }
        assertThat(generatedValues.map { it.second }).allSatisfy { assertThat(it.toIntOrNull()).isNotNull() }
    }

    @Test
    fun `fill in the blanks should complete required sibling for object-first authoritative collision owner`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "age?" to QueryParameterScalarPattern(StringPattern()),
                "name?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = false,
                    propertyKeys = setOf("age", "name"),
                    requiredPropertyKeys = setOf("name")
                )
            ),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(StringPattern())
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "info.age",
                        kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        parameterName = "info",
                        propertyName = "age"
                    )
                )
            )
        )
        val dictionary = "PARAMETERS: { QUERY: { name: Jane } }".let(Dictionary::fromYaml)

        val filledQueryParams = queryPattern.fillInTheBlanks(QueryParameters(mapOf("age" to "10")), Resolver(dictionary = dictionary)).value

        assertThat(filledQueryParams.asMap()).isEqualTo(mapOf("age" to "10", "name" to "Jane"))
    }

    @Test
    fun `fix value should repair object-first authoritative collision owner and keep required sibling`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "age?" to QueryParameterScalarPattern(StringPattern()),
                "name?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = false,
                    propertyKeys = setOf("age", "name"),
                    requiredPropertyKeys = setOf("name")
                )
            ),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(StringPattern())
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "info.age",
                        kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        parameterName = "info",
                        propertyName = "age"
                    )
                )
            )
        )
        val dictionary = "PARAMETERS: { QUERY: { age: 42 } }".let(Dictionary::fromYaml)

        val fixedQueryParams = queryPattern.fixValue(QueryParameters(mapOf("age" to "abc", "name" to "Jane")), Resolver(dictionary = dictionary))

        assertThat(fixedQueryParams.asMap()).isEqualTo(mapOf("age" to "42", "name" to "Jane"))
    }

    @Test
    fun `fill in the blanks should use dictionary value for authoritative collision owner`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("age?" to QueryParameterScalarPattern(StringPattern())),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            required = true
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "age",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        required = true
                    )
                )
            )
        )
        val dictionary = "PARAMETERS: { QUERY: { age: 42 } }".let(Dictionary::fromYaml)

        val filledQueryParams = queryPattern.fillInTheBlanks(QueryParameters(emptyMap()), Resolver(dictionary = dictionary)).value

        assertThat(filledQueryParams.asMap()).isEqualTo(mapOf("age" to "42"))
    }

    @Test
    fun `generation without row should preserve object-first authoritative collision owner combinations`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "age?" to QueryParameterScalarPattern(StringPattern()),
                "name?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = false,
                    propertyKeys = setOf("age", "name"),
                    requiredPropertyKeys = setOf("name")
                )
            ),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(StringPattern())
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "info.age",
                        kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        parameterName = "info",
                        propertyName = "age"
                    )
                )
            )
        )

        val generatedPatternKeys = queryPattern.newBasedOn(Resolver()).toList().map { it.queryPatterns.keys }

        assertThat(generatedPatternKeys).containsExactlyInAnyOrder(
            emptySet(),
            setOf("name"),
            setOf("age", "name")
        )
    }

    @Test
    fun `generation should apply multiple authoritative collision owners`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "age?" to QueryParameterScalarPattern(StringPattern()),
                "active?" to QueryParameterScalarPattern(StringPattern())
            ),
            collisionGroupsByWireKey = mapOf(
                "age" to QueryParameterCollisionGroup(
                    wireKey = "age",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "age",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(NumberPattern()),
                            required = true
                        ),
                        queryCollisionOwner(
                            wireKey = "age",
                            sourceName = "info.age",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(StringPattern()),
                            parameterName = "info",
                            propertyName = "age"
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "age",
                        sourceName = "age",
                        kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                        pattern = QueryParameterScalarPattern(NumberPattern()),
                        required = true
                    )
                ),
                "active" to QueryParameterCollisionGroup(
                    wireKey = "active",
                    owners = listOf(
                        queryCollisionOwner(
                            wireKey = "active",
                            sourceName = "filter.active",
                            kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                            pattern = QueryParameterScalarPattern(BooleanPattern()),
                            required = true,
                            parameterName = "filter",
                            propertyName = "active"
                        ),
                        queryCollisionOwner(
                            wireKey = "active",
                            sourceName = "active",
                            kind = QueryParameterCollisionOwnerKind.ScalarParameter,
                            pattern = QueryParameterScalarPattern(StringPattern())
                        )
                    ),
                    authoritativeOwner = queryCollisionOwner(
                        wireKey = "active",
                        sourceName = "filter.active",
                        kind = QueryParameterCollisionOwnerKind.FormExplodedObjectProperty,
                        pattern = QueryParameterScalarPattern(BooleanPattern()),
                        required = true,
                        parameterName = "filter",
                        propertyName = "active"
                    )
                )
            )
        )

        val generatedQueryParams = queryPattern.generate(Resolver()).toMap()

        assertThat(generatedQueryParams["age"]?.toIntOrNull()).isNotNull()
        assertThat(generatedQueryParams["active"]).isIn("true", "false")
    }

    private fun queryCollisionOwner(
        wireKey: String,
        sourceName: String,
        kind: QueryParameterCollisionOwnerKind,
        pattern: Pattern,
        required: Boolean = false,
        parameterName: String = sourceName,
        propertyName: String? = null
    ): QueryParameterCollisionOwner {
        return QueryParameterCollisionOwner(
            wireKey = wireKey,
            sourceName = sourceName,
            kind = kind,
            pattern = pattern,
            required = required,
            parameterName = parameterName,
            propertyName = propertyName
        )
    }

    @Test
    @Tag(GENERATION)
    fun `should generate required-only and all combinations for mandatory form exploded object query params from row`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "name" to QueryParameterScalarPattern(StringPattern()),
                "description?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = true,
                    propertyKeys = setOf("name", "description"),
                    requiredPropertyKeys = setOf("name")
                )
            )
        )

        val generatedPatterns = queryPattern.newBasedOn(Row(), Resolver()).toList().map { it.value.queryPatterns.keys }

        assertThat(generatedPatterns).containsExactlyInAnyOrder(
            setOf("name"),
            setOf("name", "description")
        )
    }

    @Test
    @Tag(GENERATION)
    fun `should generate required-only and all combinations for mandatory form exploded object query params without row`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "name" to QueryParameterScalarPattern(StringPattern()),
                "description?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "info",
                    required = true,
                    propertyKeys = setOf("name", "description"),
                    requiredPropertyKeys = setOf("name")
                )
            )
        )

        val generatedPatterns = queryPattern.newBasedOn(Resolver()).toList().map { it.queryPatterns.keys }

        assertThat(generatedPatterns).containsExactlyInAnyOrder(
            setOf("name"),
            setOf("name", "description")
        )
    }

    @Test
    @Tag(GENERATION)
    fun `should generate one required-only combination across multiple optional form exploded object query params`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf(
                "name?" to QueryParameterScalarPattern(StringPattern()),
                "description?" to QueryParameterScalarPattern(StringPattern()),
                "department?" to QueryParameterScalarPattern(StringPattern()),
                "location?" to QueryParameterScalarPattern(StringPattern())
            ),
            formExplodedObjectQueryParams = listOf(
                FormExplodedObjectQueryParam(
                    parameterName = "person_info",
                    required = false,
                    propertyKeys = setOf("name", "description"),
                    requiredPropertyKeys = setOf("name")
                ),
                FormExplodedObjectQueryParam(
                    parameterName = "department_info",
                    required = false,
                    propertyKeys = setOf("department", "location"),
                    requiredPropertyKeys = setOf("department")
                )
            )
        )

        val generatedPatterns = queryPattern.newBasedOn(Row(), Resolver()).toList().map { it.value.queryPatterns.keys }

        assertThat(generatedPatterns).containsExactlyInAnyOrder(
            emptySet(),
            setOf("name", "department"),
            setOf("name", "description", "department", "location")
        )
    }

    @Test
    fun `should correctly stringize a url matching having a query param with an array type`() {
        val matcher = HttpQueryParamPattern(mapOf("data" to CsvPattern(NumberPattern())))
        assertThat(matcher.toString()).isEqualTo("?data=(csv/number)")
    }

    @Nested
    inner class ReturnMultipleErrors {
        private val urlMatcher = buildQueryPattern(URI.create("http://example.com/?hello=(number)"))
        val result = urlMatcher.matches(HttpRequest("GET", "/", queryParametersMap = mapOf("hello" to "world", "hi" to "all")), Resolver()) as Failure
        private val resultText = result.toReport().toText()

        @Test
        fun `should return as many errors as there are value mismatches`() {
            assertThat(result.toMatchFailureDetailList()).hasSize(2)
        }

        @Test
        fun `keys with errors should be present in the error list`() {
            assertThat(resultText).contains(">> PARAMETERS.QUERY.hello")
            assertThat(resultText).contains(">> PARAMETERS.QUERY.hi")
        }

        @Test
        fun `key presence errors should appear before value errors`() {
            assertThat(resultText.indexOf(">> PARAMETERS.QUERY.hi")).isLessThan(resultText.indexOf(">> PARAMETERS.QUERY.hello"))
        }
    }

    @Nested
    inner class ArrayParameterUnStubbedBehaviour {
        private val unStubbedArrayQueryParameterPattern = HttpQueryParamPattern(mapOf("brand_ids" to QueryParameterArrayPattern(listOf(NumberPattern()), "brand_ids") ))
        private val enumArrayQueryParameterPattern = HttpQueryParamPattern(mapOf("brand_ids" to QueryParameterArrayPattern(listOf(EnumPattern(
            listOf(NumberValue(1), NumberValue(2))
        )), "brand_ids") ))

        @Test
        fun `matches request with single value `() {
            val result = unStubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1"))),  Resolver())
            assertThat(result is Success).isTrue
        }

        @Test
        fun `matches request with multiple values`() {
            val result = unStubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2"))),  Resolver())
            assertThat(result is Success).isTrue
        }

        @Test
        fun `fails when request contains a parameter whose type does not match the spec`() {
            val result = unStubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "abc", "brand_ids" to "def"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.brand_ids",
                    details = DefaultMismatchMessages.typeMismatch("number", "\"abc\"", "string"),
                    StandardRuleViolation.TYPE_MISMATCH
                )
            }
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.brand_ids",
                    details = DefaultMismatchMessages.typeMismatch("number", "\"def\"", "string"),
                    StandardRuleViolation.TYPE_MISMATCH
                )
            }
            """.trimIndent())
        }

        @Test
        fun `fails when request does not contain a mandatory query parameter`() {
            val result = unStubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", emptyMap()),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.brand_ids",
                    details = DefaultMismatchMessages.expectedKeyWasMissing("query param", "brand_ids"),
                    StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                )
            }
            """.trimIndent())
        }

        @Test
        fun `fails when request contains a query parameter not present in the spec`() {
            val result = unStubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "product_id" to "1"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.product_id",
                    details = DefaultMismatchMessages.unexpectedKey("query param", "product_id"),
                    StandardRuleViolation.UNKNOWN_PROPERTY
                )
            }
            """.trimIndent())
        }

        @Test
        fun `should generate the correct number of query parameters`() {
            val values = enumArrayQueryParameterPattern.generate(Resolver())
            println(values)
        }

    }

    @Nested
    inner class ArrayParameterStubbedBehaviour {
        private val stubbedArrayQueryParameterPattern = HttpQueryParamPattern(
            mapOf(
                "brand_ids" to QueryParameterArrayPattern(
                    listOf(ExactValuePattern(NumberValue(1)), ExactValuePattern(NumberValue(2))), "brand_ids"
                )
            )
        )

        @Test
        fun `matches request with exact stub values`() {
            val result = stubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2"))),  Resolver())
            assertThat(result is Success).isTrue
        }

        @Test
        fun `fails when request does not contain all the stub values`() {
            val result = stubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.brand_ids",
                    details = DefaultMismatchMessages.patternMismatch("2", "1"),
                    StandardRuleViolation.VALUE_MISMATCH
                )
            }
            """.trimIndent())
        }

        @Test
        fun `fails when request does not contain all stub values and also a value not present in the stub`() {
            val result = stubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "3"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.brand_ids",
                    details = DefaultMismatchMessages.patternMismatch("2", "1"),
                    StandardRuleViolation.VALUE_MISMATCH
                )
            }
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.brand_ids",
                    details = DefaultMismatchMessages.patternMismatch("2", "3"),
                    StandardRuleViolation.VALUE_MISMATCH
                )
            }
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.brand_ids",
                    details = DefaultMismatchMessages.patternMismatch("1", "3"),
                    StandardRuleViolation.VALUE_MISMATCH
                )
            }
            """.trimIndent())
        }

        @Test
        fun `fails when request contains all stub values and also a value not present in the stub`() {
            val result = stubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "3"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.brand_ids",
                    details = DefaultMismatchMessages.patternMismatch("2", "3"),
                    StandardRuleViolation.VALUE_MISMATCH
                )
            }
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.brand_ids",
                    details = DefaultMismatchMessages.patternMismatch("1", "3"),
                    StandardRuleViolation.VALUE_MISMATCH
                )
            }
            """.trimIndent())
        }

        @Test
        fun `fails when request does not contain a mandatory query parameter`() {
            val result = stubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", emptyMap()),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
           ${
               toViolationReportString(
                   breadCrumb = "PARAMETERS.QUERY.brand_ids",
                   details = DefaultMismatchMessages.expectedKeyWasMissing("query param", "brand_ids"),
                   StandardRuleViolation.REQUIRED_PROPERTY_MISSING
               )
           }
            """.trimIndent())
        }

        @Test
        fun `fails when request contains a query parameter not present in the spec`() {
            val result = stubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "product_id" to "1"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.product_id",
                    details = DefaultMismatchMessages.unexpectedKey("query param", "product_id"),
                    StandardRuleViolation.UNKNOWN_PROPERTY
                )
            }
            """.trimIndent())
        }

    }

    @Nested
    inner class ScalarParameterUnStubbedBehaviour {
        private val unStubbedScalarQueryParameterPattern = HttpQueryParamPattern(mapOf("product_id" to QueryParameterScalarPattern(NumberPattern())))

        @Test
        fun `matches request with single value `() {
            val result = unStubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("product_id" to "1"))),  Resolver())
            assertThat(result is Success).isTrue
        }

        @Test
        fun `fails when request contains multiple values for the parameter`() {
            val result = unStubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("product_id" to "1", "product_id" to "2"))),  Resolver())
            assertThat(result is Failure).isTrue
        }

        @Test
        fun `fails when query parameter type does not match the spec`() {
            val result = unStubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("product_id" to "abc"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.product_id",
                    details = DefaultMismatchMessages.typeMismatch("number", "\"abc\"", "string"),
                    StandardRuleViolation.TYPE_MISMATCH
                )
            }
            """.trimIndent())
        }

        @Test
        fun `fails when request does not contain a mandatory query parameter`() {
            val result = unStubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", emptyMap()),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.product_id",
                    details = DefaultMismatchMessages.expectedKeyWasMissing("query param", "product_id"),
                    StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                )
            }
            """.trimIndent())
        }

        @Test
        fun `fails when request contains a query parameter not present in the spec`() {
            val result = unStubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "product_id" to "1"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.brand_ids",
                    details = DefaultMismatchMessages.unexpectedKey("query param", "brand_ids"),
                    StandardRuleViolation.UNKNOWN_PROPERTY
                )
            }
            """.trimIndent())
        }
    }

    @Nested
    inner class ScalarParameterStubbedBehaviour {
        private val stubbedScalarQueryParameterPattern = HttpQueryParamPattern(mapOf("status" to QueryParameterScalarPattern(ExactValuePattern(StringValue("pending")))))

        @Test
        fun `matches request with exact stubbed parameter value`() {
            val result = stubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("status" to "pending"))),  Resolver())
            assertThat(result is Success).isTrue
        }

        @Test
        fun `fails when query parameter type does not match the stub`() {
            val stubbedNumericScalarQueryParameterPattern = HttpQueryParamPattern(mapOf("product_id" to QueryParameterScalarPattern(ExactValuePattern(NumberValue(1)))))
            val result = stubbedNumericScalarQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("product_id" to "abc"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.product_id",
                    details = DefaultMismatchMessages.valueMismatch("1", "\"abc\""),
                    StandardRuleViolation.VALUE_MISMATCH
                )
            }
            """.trimIndent())
        }

        @Test
        fun `fails when request does not contain a mandatory query parameter`() {
            val result = stubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", emptyMap()),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.status",
                    details = DefaultMismatchMessages.expectedKeyWasMissing("query param", "status"),
                    StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                )
            }
            """.trimIndent())
        }

        @Test
        fun `fails when request contains a query parameter not present in the spec`() {
            val result = stubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "status" to "pending"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "PARAMETERS.QUERY.brand_ids",
                    details = DefaultMismatchMessages.unexpectedKey("query param", "brand_ids"),
                    StandardRuleViolation.UNKNOWN_PROPERTY
                )
            }
            """.trimIndent())
        }
    }

    @Test
    fun `an additional query param matching additional parameters should match successfully`() {
        val queryPattern = HttpQueryParamPattern(mapOf("key" to QueryParameterScalarPattern(NumberPattern())), NumberPattern())

        val matchResult = queryPattern.matches(
            HttpRequest(queryParams = QueryParameters(mapOf("key" to "10", "data" to "20"))),
            Resolver()
        )

        assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Success::class.java)
    }

    @Test
    fun `an additional query param not matching additional parameters should not match successfully`() {
        val queryPattern = HttpQueryParamPattern(mapOf("key" to QueryParameterScalarPattern(NumberPattern())), NumberPattern())

        val matchResult = queryPattern.matches(
            HttpRequest(queryParams = QueryParameters(mapOf("key" to "10", "data" to "true"))),
            Resolver()
        )

        assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `an additional query param should not be added in a generated value`() {
        val queryPattern = HttpQueryParamPattern(mapOf("key" to QueryParameterScalarPattern(NumberPattern())), NumberPattern())

        val generatedValue = queryPattern.generate(Resolver())

        val keys = generatedValue.map { it.first }
        val values = generatedValue.map { it.second }

        assertThat(generatedValue).hasSize(1)
        assertThat(keys).containsExactly("key")
        assertThat(values).allSatisfy {
            assertThat(it.toIntOrNull()).withFailMessage("$it was expected to be a number").isNotNull()
        }
    }

    @Test
    fun `an additional query param should not be added in a test`() {
        val queryPattern = HttpQueryParamPattern(mapOf("key" to QueryParameterScalarPattern(NumberPattern())), NumberPattern())

        val generatedValue = queryPattern.newBasedOn(Row(), Resolver()).toList().map { it.value.queryPatterns }

        assertThat(generatedValue).hasSize(1)
        assertThat(generatedValue.first()).containsOnlyKeys("key")
    }

    @Test
    fun `valid additional query params should be preserved when fixing values`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("key" to QueryParameterScalarPattern(NumberPattern())),
            NumberPattern()
        )

        val fixedValue = queryPattern.fixValue(QueryParameters(mapOf("key" to "10", "extra" to "20")), Resolver())

        assertThat(fixedValue.asMap()).containsEntry("extra", "20")
    }

    @Test
    fun `invalid additional query params should be fixed when fixing values`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("key" to QueryParameterScalarPattern(NumberPattern())),
            NumberPattern()
        )

        val fixedValue = queryPattern.fixValue(QueryParameters(mapOf("key" to "10", "extra" to "abc")), Resolver())

        assertThat(fixedValue.asMap()["extra"]).isNotEqualTo("abc")
        assertThat(fixedValue.asMap()["extra"]?.toDoubleOrNull()).isNotNull()
    }

    @Test
    fun `valid additional query params should be preserved when filling blanks`() {
        val queryPattern = HttpQueryParamPattern(
            mapOf("key" to QueryParameterScalarPattern(NumberPattern())),
            NumberPattern()
        )

        val filledValue = queryPattern.fillInTheBlanks(QueryParameters(mapOf("key" to "10", "extra" to "20")), Resolver()).value

        assertThat(filledValue.asMap()).containsEntry("extra", "20")
    }

    @Test
    fun `should replace any non-encodable characters from values provided by StringProviders`() {
        val pattern = HttpQueryParamPattern(mapOf("userName" to QueryParameterScalarPattern(StringPattern())))
        val resolver = Resolver()
        val provider = object: StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>): String = "specmatic test"
        }

        StringProviders.with(provider) {
            val generated = pattern.generate(resolver).toMap()
            assertThat(generated["userName"]).isEqualTo("specmatic_test")
        }
    }

    @Nested
    inner class FixValueTests {
        @Test
        fun `should be able to add missing values`() {
            val queryPattern = HttpQueryParamPattern(mapOf("petId" to NumberPattern(), "owner" to StringPattern()))
            val invalidValue = QueryParameters(listOf("petId" to "123"))

            val dictionary = "PARAMETERS: { QUERY: { owner: TODO } }".let(Dictionary::fromYaml)
            val fixedValue = queryPattern.fixValue(invalidValue, Resolver(dictionary = dictionary))
            println(fixedValue)

            assertThat(fixedValue.paramPairs).isNotEmpty
            assertThat(fixedValue.paramPairs).containsExactlyInAnyOrderElementsOf(listOf(
                "petId" to "123",
                "owner" to "TODO"
            ))
        }

        @Test
        fun `should add missing required property when optional form exploded object query param is partially present`() {
            val queryPattern = HttpQueryParamPattern(
                mapOf(
                    "name?" to QueryParameterScalarPattern(StringPattern()),
                    "description?" to QueryParameterScalarPattern(StringPattern())
                ),
                formExplodedObjectQueryParams = listOf(
                    FormExplodedObjectQueryParam(
                        parameterName = "info",
                        required = false,
                        propertyKeys = setOf("name", "description"),
                        requiredPropertyKeys = setOf("name")
                    )
                )
            )
            val invalidValue = QueryParameters(listOf("description" to "buyer"))

            val dictionary = "PARAMETERS: { QUERY: { name: Jane } }".let(Dictionary::fromYaml)
            val fixedValue = queryPattern.fixValue(invalidValue, Resolver(dictionary = dictionary))

            assertThat(fixedValue.keys).containsExactlyInAnyOrder("name", "description")
            assertThat(fixedValue.getValues("description")).containsExactly("buyer")
        }

        @Test
        fun `should not add missing optional keys`() {
            val queryPattern = HttpQueryParamPattern(mapOf("petId" to NumberPattern(), "owner?" to StringPattern()))

            val value = QueryParameters(listOf("petId" to "123"))
            val fixedValue = queryPattern.fixValue(value, Resolver())
            println(fixedValue)

            assertThat(fixedValue.paramPairs).isNotEmpty
            assertThat(fixedValue.paramPairs).containsExactlyInAnyOrderElementsOf(value.paramPairs)
        }

        @Test
        fun `should be able to fix invalid values`() {
            val queryPattern = HttpQueryParamPattern(mapOf("petId" to NumberPattern(), "owner" to EmailPattern()))
            val invalidValue = QueryParameters(listOf("petId" to "TODO", "owner" to "999"))

            val dictionary = "PARAMETERS: { QUERY: { petId: 123, owner: fixed@specmatic.io } }".let(Dictionary::fromYaml)
            val fixedValue = queryPattern.fixValue(invalidValue, Resolver(dictionary = dictionary))
            println(fixedValue)

            assertThat(fixedValue.paramPairs).isNotEmpty
            assertThat(fixedValue.paramPairs).containsExactlyInAnyOrderElementsOf(listOf(
                "petId" to "123",
                "owner" to "fixed@specmatic.io"
            ))
        }

        @Test
        fun `should allow extra keys in the value when EXTENSIBLE_QUERY_PARAMS is set`() {
            val queryPattern = HttpQueryParamPattern(
                mapOf("petId" to NumberPattern(), "owner" to StringPattern()),
                extensibleQueryParams = true
            )
            val value = QueryParameters(listOf("petId" to "999", "owner" to "TODO", "extra" to "value"))

            val fixedValue = queryPattern.fixValue(value, Resolver())
            println(fixedValue)

            assertThat(fixedValue.paramPairs).isNotEmpty
            assertThat(fixedValue.paramPairs).isEqualTo(value.paramPairs)
        }

        @Test
        fun `should not allow extra keys in the value when EXTENSIBLE_QUERY_PARAMS is not set`() {
            val queryPattern = HttpQueryParamPattern(mapOf("petId" to NumberPattern(), "owner" to StringPattern()))
            val value = QueryParameters(listOf("petId" to "999", "owner" to "TODO", "extra" to "value"))

            val fixedValue = queryPattern.fixValue(value, Resolver())
            println(fixedValue)

            assertThat(fixedValue.paramPairs).isNotEmpty
            assertThat(fixedValue.paramPairs).containsExactlyInAnyOrderElementsOf(listOf(
                "petId" to "999",
                "owner" to "TODO"
            ))
        }

        @Test
        fun `should not generate optional keys when initial value is null or empty`() {
            val queryPattern = HttpQueryParamPattern(mapOf("petId" to NumberPattern(), "owner?" to StringPattern()))
            val dictionary = "{ (number): 999, (boolean): true }".let(Dictionary::fromYaml)

            val emptyValue = QueryParameters(emptyList())
            val emptyFixedValue = queryPattern.fixValue(emptyValue, Resolver(dictionary=dictionary))
            println(emptyFixedValue)

            val nullValue = null
            val nullFixedValue = queryPattern.fixValue(nullValue, Resolver(dictionary=dictionary))
            println(nullFixedValue)

            assertThat(emptyFixedValue).isEqualTo(nullFixedValue)
            assertThat(emptyFixedValue.paramPairs).containsExactlyInAnyOrderElementsOf(listOf(
                "petId" to "999"
            ))
        }

        @Test
        fun `should override unexpectedKeyCheck set by flagBased or anything before it`() {
            val queryPattern = HttpQueryParamPattern(mapOf("petId" to NumberPattern(), "owner" to StringPattern()))

            val value = QueryParameters(listOf("petId" to "999", "owner" to "TODO", "extra" to "value"))
            val fixedValue = queryPattern.fixValue(value, Resolver().withUnexpectedKeyCheck(IgnoreUnexpectedKeys))
            println(fixedValue)

            assertThat(fixedValue.paramPairs).containsExactlyInAnyOrderElementsOf(listOf(
                "petId" to "999",
                "owner" to "TODO"
            ))
        }

        @Test
        fun `should retain pattern token if it matches when resolver is in mock mode`() {
            val httpQueryPattern = HttpQueryParamPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val validValue = QueryParameters(mapOf("number" to "(number)", "boolean" to "(boolean)"))
            val fixedValue = httpQueryPattern.fixValue(validValue, Resolver(mockMode = true))

            println(fixedValue)
            assertThat(fixedValue).isEqualTo(validValue)
        }

        @Test
        fun `should generate value when pattern token does not match when resolver is in mock mode`() {
            val httpQueryPattern = HttpQueryParamPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val validValue = QueryParameters(mapOf("number" to "(string)", "boolean" to "(string)"))
            val dictionary = "{ (number): 999, (boolean): true }".let(Dictionary::fromYaml)
            val fixedValue = httpQueryPattern.fixValue(validValue, Resolver(mockMode = true, dictionary = dictionary))

            println(fixedValue)
            assertThat(fixedValue.asMap()).isEqualTo(mapOf("number" to "999", "boolean" to "true"))
        }

        @Test
        fun `should generate values even if pattern token matches but resolver is not in mock mode`() {
            val httpQueryPattern = HttpQueryParamPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val validValue = QueryParameters(mapOf("number" to "(number)", "boolean" to "(boolean)"))
            val dictionary = "{ (number): 999, (boolean): true }".let(Dictionary::fromYaml)
            val fixedValue = httpQueryPattern.fixValue(validValue, Resolver(dictionary = dictionary))

            println(fixedValue)
            assertThat(fixedValue.asMap()).isEqualTo(mapOf("number" to "999", "boolean" to "true"))
        }

        @Test
        fun `should not add missing mandatory keys when resolver is set to partial`() {
            val httpQueryPattern = HttpQueryParamPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val dictionary = "{ (number): 999, (boolean): true }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary).partializeKeyCheck()
            val partialInvalidValue = QueryParameters(mapOf("number" to "(string)"))
            val fixedValue = httpQueryPattern.fixValue(partialInvalidValue, resolver)

            assertThat(fixedValue.asMap()).isEqualTo(mapOf("number" to "999"))
        }
    }

    @Nested
    inner class FillInTheBlanksTests {
        @Test
        fun `should generate values for missing mandatory keys and pattern tokens`() {
            val queryParams = HttpQueryParamPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val params = QueryParameters(mapOf("number" to "(number)"))
            val dictionary = "PARAMETERS: { QUERY: { number: 999, boolean: true } }".let(Dictionary::fromYaml)
            val filledParams = queryParams.fillInTheBlanks(params, Resolver(dictionary = dictionary)).value

            assertThat(filledParams.asMap()).isEqualTo(mapOf("number" to "999", "boolean" to "true"))
        }

        @Test
        fun `should not generate missing optional keys`() {
            val queryParams = HttpQueryParamPattern(mapOf("number" to NumberPattern(), "boolean?" to BooleanPattern()))
            val params = QueryParameters(mapOf("number" to "999"))
            val filledParams = queryParams.fillInTheBlanks(params, Resolver()).value

            assertThat(filledParams.asMap()).isEqualTo(mapOf("number" to "999"))
        }

        @Test
        fun `should handle any-value pattern token as a special case`() {
            val queryParams = HttpQueryParamPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val params = QueryParameters(mapOf("number" to "(anyvalue)"))
            val dictionary = "PARAMETERS: { QUERY: { number: 999, boolean: true } }".let(Dictionary::fromYaml)
            val filledParams = queryParams.fillInTheBlanks(params, Resolver(dictionary = dictionary)).value

            assertThat(filledParams.asMap()).isEqualTo(mapOf("number" to "999", "boolean" to "true"))
        }

        @Test
        fun `should complain when pattern-token does not match the underlying pattern`() {
            val queryParams = HttpQueryParamPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val params = QueryParameters(mapOf("number" to "(string)"))
            val exception = assertThrows<ContractException> { queryParams.fillInTheBlanks(params, Resolver()).value }

            assertThat(exception.failure().reportString()).isEqualToNormalizingWhitespace(
                toViolationReportString(
                    breadCrumb = "number",
                    details = DefaultMismatchMessages.patternMismatch("number", "string"),
                    StandardRuleViolation.TYPE_MISMATCH
                )
            )
        }

        @Test
        fun `should generate missing optional keys when allPatternsMandatory is set`() {
            val queryParams = HttpQueryParamPattern(mapOf("number" to NumberPattern(), "boolean?" to BooleanPattern()))
            val params = QueryParameters(mapOf("number" to "999"))
            val dictionary = "PARAMETERS: { QUERY: { boolean: true } }".let(Dictionary::fromYaml)
            val filledParams = queryParams.fillInTheBlanks(
                params, Resolver(dictionary = dictionary).withAllPatternsAsMandatory()
            ).value

            assertThat(filledParams.asMap()).isEqualTo(mapOf("number" to "999", "boolean" to "true"))
        }

        @Test
        fun `should not generate missing mandatory keys when resolver is set to negative`() {
            val queryParams = HttpQueryParamPattern(mapOf("number" to NumberPattern(), "boolean?" to BooleanPattern()))
            val params = QueryParameters(mapOf("boolean" to "true"))
            val filledParams = queryParams.fillInTheBlanks(params, Resolver(isNegative = true)).value

            assertThat(filledParams.asMap()).isEqualTo(mapOf("boolean" to "true"))
        }

        @Test
        fun `should allow extra keys when extensible-query-params or resolver is negative`() {
            val queryParamsPattern = HttpQueryParamPattern(
                mapOf("number" to NumberPattern()),
                extensibleQueryParams = true
            )
            val queryParameters = mapOf("number" to "(number)", "extraKey" to "(string)")
            val dictionary = "PARAMETERS: { QUERY: { number: 999, extraKey: ExtraValue } }".let(Dictionary::fromYaml)

            val negativeResolver = Resolver(dictionary = dictionary, isNegative = true)
            val negativeFilledParams = queryParamsPattern.fillInTheBlanks(QueryParameters(queryParameters), negativeResolver).value
            assertThat(negativeFilledParams.asMap()).isEqualTo(
                mapOf("number" to "999", "extraKey" to "ExtraValue")
            )

            val resolver = Resolver(dictionary = dictionary)
            val filledParams = queryParamsPattern.fillInTheBlanks(QueryParameters(queryParameters), resolver).value
            assertThat(filledParams.asMap()).isEqualTo(
                mapOf("number" to "999", "extraKey" to "ExtraValue")
            )
        }

        @Test
        fun `should allow invalid pattern tokens when resolver is negative`() {
            val queryParamsPattern = HttpQueryParamPattern(mapOf("test" to StringPattern()))
            val invalidPatterns = listOf(
                ListPattern(StringPattern()),
                BooleanPattern(),
                NullPattern,
            )


            assertThat(invalidPatterns).allSatisfy {
                val resolver = Resolver(newPatterns = mapOf("(Test)" to it), isNegative = true)
                val value = QueryParameters(mapOf("test" to "(Test)"))
                val result = queryParamsPattern.fillInTheBlanks(value, resolver)

                assertThat(result).isInstanceOf(HasValue::class.java); result as HasValue
                println(result.value)
            }
        }
    }
}
