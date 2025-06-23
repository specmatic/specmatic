package io.specmatic.core

import io.mockk.every
import io.mockk.mockk
import io.specmatic.GENERATION
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXTENSIBLE_QUERY_PARAMS
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.trimmedLinesList
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
                MatchFailureDetails(listOf(BreadCrumb.PARAM_QUERY.value, "petid"), listOf("""Expected number, actual was "text""""))
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
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.brand_ids

                   Expected number, actual was "abc"
                
                >> PARAMETERS.QUERY.brand_ids
                
                   Expected number, actual was "def"
            """.trimIndent().trimmedLinesList())
        }

        @Test
        fun `fails when request does not contain a mandatory query parameter`() {
            val result = unStubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", emptyMap()),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.brand_ids

                   Expected query param named "brand_ids" was missing
            """.trimIndent().trimmedLinesList())
        }

        @Test
        fun `fails when request contains a query parameter not present in the spec`() {
            val result = unStubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "product_id" to "1"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.product_id

                   Query param named "product_id" was unexpected
            """.trimIndent().trimmedLinesList())
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
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.brand_ids

                   Expected 2 (number), actual was 1 (number)
            """.trimIndent().trimmedLinesList())
        }

        @Test
        fun `fails when request does not contain all stub values and also a value not present in the stub`() {
            val result = stubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "3"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.brand_ids

                   Expected 2 (number), actual was 1 (number)
                
                >> PARAMETERS.QUERY.brand_ids
                
                   Expected 2 (number), actual was 3 (number)
                
                >> PARAMETERS.QUERY.brand_ids
                
                   Expected 1 (number), actual was 3 (number)
            """.trimIndent().trimmedLinesList())
        }

        @Test
        fun `fails when request contains all stub values and also a value not present in the stub`() {
            val result = stubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "3"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.brand_ids

                   Expected 2 (number), actual was 3 (number)
                
                >> PARAMETERS.QUERY.brand_ids
                
                   Expected 1 (number), actual was 3 (number)
            """.trimIndent().trimmedLinesList())
        }

        @Test
        fun `fails when request does not contain a mandatory query parameter`() {
            val result = stubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", emptyMap()),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.brand_ids

                   Expected query param named "brand_ids" was missing
            """.trimIndent().trimmedLinesList())
        }

        @Test
        fun `fails when request contains a query parameter not present in the spec`() {
            val result = stubbedArrayQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "product_id" to "1"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.product_id

                   Query param named "product_id" was unexpected
            """.trimIndent().trimmedLinesList())
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
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.product_id

                   Expected number, actual was "abc"
            """.trimIndent().trimmedLinesList())
        }

        @Test
        fun `fails when request does not contain a mandatory query parameter`() {
            val result = unStubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", emptyMap()),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.product_id

                   Expected query param named "product_id" was missing
            """.trimIndent().trimmedLinesList())
        }

        @Test
        fun `fails when request contains a query parameter not present in the spec`() {
            val result = unStubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "product_id" to "1"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.brand_ids

                   Query param named "brand_ids" was unexpected
            """.trimIndent().trimmedLinesList())
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
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.product_id

                   Expected 1 (number), actual was "abc"
            """.trimIndent().trimmedLinesList())
        }

        @Test
        fun `fails when request does not contain a mandatory query parameter`() {
            val result = stubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", emptyMap()),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.status

                   Expected query param named "status" was missing
            """.trimIndent().trimmedLinesList())
        }

        @Test
        fun `fails when request contains a query parameter not present in the spec`() {
            val result = stubbedScalarQueryParameterPattern.matches(HttpRequest("GET", "/", queryParams = QueryParameters(paramPairs = listOf("brand_ids" to "1", "status" to "pending"))),  Resolver())
            assertThat(result is Failure).isTrue
            assertThat(result.reportString().trimmedLinesList()).isEqualTo("""
                >> PARAMETERS.QUERY.brand_ids

                   Query param named "brand_ids" was unexpected
            """.trimIndent().trimmedLinesList())
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
    fun `an additional query param should be added in a generated value`() {
        val queryPattern = HttpQueryParamPattern(mapOf("key" to QueryParameterScalarPattern(NumberPattern())), NumberPattern())

        val generatedValue = queryPattern.generate(Resolver())

        val keys = generatedValue.map { it.first }
        val values = generatedValue.map { it.second }

        assertThat(generatedValue).hasSize(2)
        assertThat(keys).contains("key")
        assertThat(keys.filter { it != "key" }).hasSize(1)
        assertThat(values).allSatisfy {
            assertThat(it.toIntOrNull()).withFailMessage("$it was expected to be a number").isNotNull()
        }
    }

    @Test
    fun `an additional query param should be added in a test`() {
        val queryPattern = HttpQueryParamPattern(mapOf("key" to QueryParameterScalarPattern(NumberPattern())), NumberPattern())

        val generatedValue = queryPattern.newBasedOn(Row(), Resolver()).toList().map { it.value.queryPatterns }

        assertThat(generatedValue).hasSize(1)
        assertThat(generatedValue.first()).hasSize(2)
        assertThat(generatedValue.first().keys).contains("key")
        assertThat(generatedValue.first().keys.filter { it != "key" }).hasSize(1)
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
            val queryPattern = HttpQueryParamPattern(mapOf("petId" to NumberPattern(), "owner" to StringPattern()))
            val invalidValue = QueryParameters(listOf("petId" to "TODO", "owner" to "999"))

            val dictionary = "PARAMETERS: { QUERY: { petId: 123, owner: TODO } }".let(Dictionary::fromYaml)
            val fixedValue = queryPattern.fixValue(invalidValue, Resolver(dictionary = dictionary))
            println(fixedValue)

            assertThat(fixedValue.paramPairs).isNotEmpty
            assertThat(fixedValue.paramPairs).containsExactlyInAnyOrderElementsOf(listOf(
                "petId" to "123",
                "owner" to "TODO"
            ))
        }

        @Test
        fun `should allow extra keys in the value when EXTENSIBLE_QUERY_PARAMS is set`() {
            val queryPattern = HttpQueryParamPattern(mapOf("petId" to NumberPattern(), "owner" to StringPattern()))
            val value = QueryParameters(listOf("petId" to "999", "owner" to "TODO", "extra" to "value"))

            val fixedValue = Flags.using(EXTENSIBLE_QUERY_PARAMS to "true") {
                queryPattern.fixValue(value, Resolver())
            }
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

            assertThat(exception.failure().reportString()).isEqualToNormalizingWhitespace("""
            >> number
            Expected number, actual was string
            """.trimIndent())
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
            val queryParamsPattern = HttpQueryParamPattern(mapOf("number" to NumberPattern()))
            val queryParameters = mapOf("number" to "(number)", "extraKey" to "(string)")
            val dictionary = "PARAMETERS: { QUERY: { number: 999, extraKey: ExtraValue } }".let(Dictionary::fromYaml)

            val negativeResolver = Resolver(dictionary = dictionary, isNegative = true)
            val negativeFilledParams = queryParamsPattern.fillInTheBlanks(QueryParameters(queryParameters), negativeResolver).value
            assertThat(negativeFilledParams.asMap()).isEqualTo(
                mapOf("number" to "999", "extraKey" to "ExtraValue")
            )
            
            Flags.using(EXTENSIBLE_QUERY_PARAMS to "true") {
                val resolver = Resolver(dictionary = dictionary)
                val filledParams = queryParamsPattern.fillInTheBlanks(QueryParameters(queryParameters), resolver).value
                assertThat(filledParams.asMap()).isEqualTo(
                    mapOf("number" to "999", "extraKey" to "ExtraValue")
                )
            }
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