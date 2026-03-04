package io.specmatic.core

import io.specmatic.GENERATION
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.*
import io.specmatic.toViolationReportString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URISyntaxException

internal class HttpPathPatternTest {

    @ParameterizedTest
    @CsvSource(
        "a/b/1",
        "a/1/c",
        "1/b/c",
        "1/2/c",
        "a/1/2",
        "1/b/2",
        "1/2/3"
    )
    fun `should match path when structure matches and not all segments conflict`(path: String) {
        val pattern = HttpPathPattern.from("/(first:string)/(second:string)/(third:string)")
        val result = pattern.matches(path, Resolver())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should match not path when structure does matches and there are some segment conflicts`() {
        val pattern = HttpPathPattern.from("/(first:number)/(second:string)/(third:string)")
        val result = pattern.matches("/a/b/1", Resolver())
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should return success when path matches and no other patterns conflict based on specificity`() {
        // Create a pattern with other less specific patterns that should not conflict
        val lessSpecificPattern1 = HttpPathPattern.from("/(first:string)/(second:string)/(third:string)")
        val lessSpecificPattern2 = HttpPathPattern.from("/(param:string)/(second:string)")
        val pattern = HttpPathPattern.from("/a/b/c").copy(otherPathPatterns = listOf(lessSpecificPattern1, lessSpecificPattern2))
        val result = pattern.matches("a/b/c", Resolver())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should return failure when path matches a more specific pattern based on specificity`() {
        // Create a more specific pattern (with more ExactValuePattern segments)
        val moreSpecificPattern = HttpPathPattern.from("/api/v1/users")
        // Create a less specific pattern (with fewer ExactValuePattern segments)
        val lessSpecificPattern = HttpPathPattern.from("/(section:string)/(version:string)/users").copy(otherPathPatterns = listOf(moreSpecificPattern))
        val result = lessSpecificPattern.matches("api/v1/users", Resolver())
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).reportString()).contains("matches a more specific pattern")
    }

    @Test
    fun `should not conflict when otherPathPatterns is empty representing different HTTP methods`() {
        val postPattern = HttpPathPattern.from("/(section:string)/(version:string)/users")
        // This should succeed because there are no conflicting patterns in the same HTTP method
        val result = postPattern.matches("/api/v1/users", Resolver())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @ParameterizedTest
    @CsvSource(
        "/pets/(id:number), /pets/abc",
        "/customers/(customerId:number)/profile, /customers/abc/profile",
        "/(apiVersion:number), /abc",
        "/(apiVersion:number)/api, /abc/api",
        "/(apiVersion:number)/api/(id:number), /abc/api/abc",
    )
    fun `failure reason should contain structure match when structure matches`(pathPattern: String, path: String) {
        val pattern = buildHttpPathPattern(pathPattern)
        val result = pattern.matches(URI(path), Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).failureReason).isEqualTo(FailureReason.URLPathParamMismatchButSameStructure)
    }

    @ParameterizedTest
    @CsvSource(
        "/pets/(id:number), /pets/123/123",
        "/customers/(customerId:number)/profile, /123/customers/123/profile",
        "/(apiVersion:number), /123/abc",
        "/(apiVersion:number)/api, /api",
        "/(apiVersion:number)/api/(id:number), /123/api",
        "/user/profile/(id:number), /user/profile/",
    )
    fun `failure reason should not contain structure match when structure does not match`(pathPattern: String, path: String) {
        val pattern = buildHttpPathPattern(pathPattern)
        val result = pattern.matches(URI(path), Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).failureReason).isEqualTo(FailureReason.URLPathMisMatch)
    }

    @Test
    @Throws(URISyntaxException::class, UnsupportedEncodingException::class)
    fun `should not match url when number of path parts do not match`() {
        val urlPattern = buildHttpPathPattern(URI("/pets/123/owners/hari"))
        urlPattern.matches(URI("/pets/123/owners"), Resolver()).let {
            assertThat(it is Result.Failure).isTrue()
            assertThat((it as Result.Failure).toMatchFailureDetails()).isEqualTo(
                MatchFailureDetails(
                    listOf("PATH"),
                    listOf("""Expected /pets/123/owners (having 3 path segments) to match /pets/123/owners/hari (which has 4 path segments).""")
                )
            )
        }
    }

    @Test
    @Throws(URISyntaxException::class, UnsupportedEncodingException::class)
    fun `should match url with only path parameters`() {
        val urlPattern = buildHttpPathPattern(URI("/pets/(petid:number)/owner/(owner:string)"))
        urlPattern.matches(URI("/pets/123123/owner/hari")).let {
            assertThat(it is Result.Success).isTrue()
        }
    }

    @Test
    @Throws(URISyntaxException::class, UnsupportedEncodingException::class)
    fun `should not match when all parts of the path do not match`() {
        val urlPattern = buildHttpPathPattern(URI("/pets/(petid:number)"))
        urlPattern.matches(URI("/owners/123123"), Resolver()).let {
            assertThat(it is Result.Failure).isTrue()
            assertThat((it as Result.Failure).toMatchFailureDetails()).isEqualTo(
                MatchFailureDetails(
                    listOf("PARAMETERS.PATH (/owners/123123)"),
                    listOf("""Expected "/pets/", actual was "/owners/"""")
                )
            )
        }
    }

    @Test
    fun `should generate path when URI contains only query parameters`() {
        val urlPattern = buildHttpPathPattern(URI("/pets?petid=(number)"))
        urlPattern.generate(Resolver()).let {
            assertThat(it).isEqualTo("/pets")
        }
    }

    @Test
    fun `should generate path when url has only path parameters`() {
        val urlPattern = buildHttpPathPattern(URI("/pets/(petid:number)/owner/(owner:string)"))
        val resolver = mockk<Resolver>().also {
            every {
                it.updateLookupPath("PARAMETERS", any())
            } returns it
            every {
                it.updateLookupForParam("PATH")
            } returns it
            every {
                it.withCyclePrevention<StringValue>(
                    ExactValuePattern(StringValue("/pets/")),
                    any()
                )
            } returns StringValue("/pets/")
            every {
                it.withCyclePrevention<NumberValue>(NumberPattern(), any())
            } returns NumberValue(123)
            every {
                it.withCyclePrevention<StringValue>(
                    ExactValuePattern(StringValue("/owner/")),
                    any()
                )
            } returns StringValue("/owner/")
            every {
                it.withCyclePrevention<StringValue>(StringPattern(), any())
            } returns StringValue("hari")
        }
        urlPattern.generate(resolver).let {
            assertThat(it).isEqualTo("/pets/123/owner/hari")
        }
    }

    @Test
    @Tag(GENERATION)
    fun `should pick up facts`() {
        val urlPattern = buildHttpPathPattern(URI("/pets/(id:number)"))
        val resolver = Resolver(mapOf("id" to StringValue("10")))

        val newURLPatterns = urlPattern.newBasedOn(Row(), resolver)
        val urlPathSegmentPatterns = newURLPatterns.first()
        assertEquals(2, urlPathSegmentPatterns.size)
        val path = urlPathSegmentPatterns.joinToString("") { it.generate(resolver).toStringLiteral() }
        assertEquals("/pets/10", path)
    }

    @Test
    fun `request url with no query params should match a url pattern with query params`() {
        val matcher = buildHttpPathPattern(URI("/pets?id=(string)"))
        assertThat(matcher.matches(URI("/pets"))).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should match a number in a path only when resolver has mock matching on`() {
        val matcher = buildHttpPathPattern(URI("/pets/(id:number)"))
        assertThat(
            matcher.matches(
                URI.create("/pets/10"),
                Resolver()
            )
        ).isInstanceOf(Result.Success::class.java)
        assertThat(
            matcher.matches(
                URI.create("/pets/(id:number)"),
                Resolver(mockMode = true)
            )
        ).isInstanceOf(Result.Success::class.java)
        assertThat(
            matcher.matches(
                URI.create("/pets/(id:number)"),
                Resolver(mockMode = false)
            )
        ).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should match a boolean in a path only when resolver has mock matching on`() {
        val matcher = buildHttpPathPattern(URI("/pets/(status:boolean)"))
        assertThat(
            matcher.matches(
                URI.create("/pets/true"),
                Resolver()
            )
        ).isInstanceOf(Result.Success::class.java)
        assertThat(
            matcher.matches(
                URI.create("/pets/(status:boolean)"),
                Resolver(mockMode = true)
            )
        ).isInstanceOf(Result.Success::class.java)
        assertThat(
            matcher.matches(
                URI.create("/pets/(status:boolean)"),
                Resolver(mockMode = false)
            )
        ).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should generate negative patterns for path containing params between fixed segments`() {
        val pattern = buildHttpPathPattern("/products/(id:number)/image")
        val negativePatterns = pattern.negativeBasedOn(Row(), Resolver()).toList()
        assertThat(negativePatterns).hasSize(2)
        assertThat(negativePatterns[0].value).containsExactlyElementsOf(
            listOf(
                URLPathSegmentPattern(ExactValuePattern(StringValue("/products/"))),
                URLPathSegmentPattern(BooleanPattern(), "id"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("/image"))),
            )
        )
        assertThat(negativePatterns[1].value).containsExactlyElementsOf(
            listOf(
                URLPathSegmentPattern(ExactValuePattern(StringValue("/products/"))),
                URLPathSegmentPattern(StringPattern(), "id"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("/image"))),
            )
        )
    }

    @Test
    fun `should generate negative patterns for path containing alternating fixed segments and params`() {
        assertThat(listOf(URLPathSegmentPattern(NumberPattern(), "orgId"))).isEqualTo(listOf(URLPathSegmentPattern(NumberPattern(), "orgId")))
        val pattern = buildHttpPathPattern("/organizations/(orgId:number)/employees/(empId:number)")
        val negativePatterns = pattern.negativeBasedOn(Row(), Resolver()).toList()
        assertThat(negativePatterns).hasSize(4)
        assertThat(negativePatterns[0].value).containsExactlyElementsOf(
            listOf(
                URLPathSegmentPattern(ExactValuePattern(StringValue("/organizations/"))),
                URLPathSegmentPattern(BooleanPattern(), "orgId"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("/employees/"))),
                URLPathSegmentPattern(NumberPattern(), "empId"),
            )
        )
        assertThat(negativePatterns[1].value).containsExactlyElementsOf(
            listOf(
                URLPathSegmentPattern(ExactValuePattern(StringValue("/organizations/"))),
                URLPathSegmentPattern(StringPattern(), "orgId"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("/employees/"))),
                URLPathSegmentPattern(NumberPattern(), "empId"),
            )
        )
        assertThat(negativePatterns[2].value).containsExactlyElementsOf(
            listOf(
                URLPathSegmentPattern(ExactValuePattern(StringValue("/organizations/"))),
                URLPathSegmentPattern(NumberPattern(), "orgId"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("/employees/"))),
                URLPathSegmentPattern(BooleanPattern(), "empId"),
            )
        )
        assertThat(negativePatterns[3].value).containsExactlyElementsOf(
            listOf(
                URLPathSegmentPattern(ExactValuePattern(StringValue("/organizations/"))),
                URLPathSegmentPattern(NumberPattern(), "orgId"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("/employees/"))),
                URLPathSegmentPattern(StringPattern(), "empId"),
            )
        )
    }

    @Test
    fun `should return all path param errors together`() {
        val pattern = buildHttpPathPattern("/pets/(petid:number)/file/(fileid:number)")
        val mismatchResult = pattern.matches(URI("/pets/abc/file/def")) as Result.Failure

        assertThat(mismatchResult.failureReason).isEqualTo(FailureReason.URLPathParamMismatchButSameStructure)
        assertThat(mismatchResult.reportString())
            .contains("PATH.petid")
            .contains("PATH.fileid")
    }

    @Test
    fun `should match and extract interpolated path parameters`() {
        val pattern = buildHttpPathPattern("/product/product-(id:string)/order/order-(orderId:string)/latest")
        assertThat(pattern.matches(URI("/product/product-12/order/order-abc/latest"), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.extractPathParams("/product/product-12/order/order-abc/latest", Resolver())).isEqualTo(mapOf("id" to "12", "orderId" to "abc"))
    }

    @Test
    fun `should match interpolated path when literal contains plus`() {
        val pattern = buildHttpPathPattern("/v1+beta/item-(id:string)")
        assertThat(pattern.matches(URI("/v1+beta/item-abc"), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.extractPathParams("/v1+beta/item-abc", Resolver())).isEqualTo(mapOf("id" to "abc"))
    }

    @Test
    fun `should match interpolated path when literal contains dot`() {
        val pattern = buildHttpPathPattern("/v1.2/item-(id:string)")
        assertThat(pattern.matches(URI("/v1.2/item-xyz"), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.extractPathParams("/v1.2/item-xyz", Resolver())).isEqualTo(mapOf("id" to "xyz"))
    }

    @Test
    fun `should fail interpolated path match when literal prefixes inside segment do not match`() {
        val pattern = buildHttpPathPattern("/product/product-(id:string)/order/order-(orderId:string)/latest")
        val mismatchResult = pattern.matches(URI("/product/12/order/abc/latest"), Resolver()) as Result.Failure
        assertThat(mismatchResult.failureReason).isEqualTo(FailureReason.URLPathMisMatch)
    }

    @Test
    fun `should return failure reason as url mismatch if there is even one literal path segment mismatch`() {
        val pattern = buildHttpPathPattern("/pets/(id:number)/data")
        val mismatchResult = pattern.matches(URI("/pets/abc/info")) as Result.Failure
        assertThat(mismatchResult.failureReason).isEqualTo(FailureReason.URLPathMisMatch)
    }

    @Test
    @Tag(GENERATION)
    fun `should generate a path with a concrete value given a path pattern with newBasedOn`() {
        val matcher = buildHttpPathPattern(URI("/pets/(status:boolean)"))
        val matchers = matcher.newBasedOn(Row(), Resolver()).toList()
        assertThat(matchers).hasSize(1)
        assertThat(matchers.single()).isEqualTo(
            listOf(
                URLPathSegmentPattern(ExactValuePattern(StringValue("/pets/"))),
                URLPathSegmentPattern(BooleanPattern(), "status")
            )
        )
    }

    @Tag(GENERATION)
    @Test
    fun `should generate negative values for a number`() {
        val headers = HttpHeadersPattern(mapOf("X-TraceID" to NumberPattern()))
        val newHeaders = headers.negativeBasedOn(Row(), Resolver(), BreadCrumb.PARAM_HEADER.value).map { it.value }.toList()

        assertThat(newHeaders).containsExactlyInAnyOrder(
            HttpHeadersPattern(mapOf("X-TraceID" to StringPattern())),
            HttpHeadersPattern(mapOf("X-TraceID" to BooleanPattern())),
            HttpHeadersPattern(mapOf())
        )
    }

    @Tag(GENERATION)
    @Test
    fun `should generate negative path params with annotations`() {
        val pathPattern = buildHttpPathPattern("/pet/(id:number)")

        val negativePathPatterns = pathPattern.negativeBasedOn(Row(mapOf("id" to "10")), Resolver()).toList()

        assertThat(negativePathPatterns).hasSize(2)

        negativePathPatterns.filter {
            val value = it as? HasValue ?: fail("Expected HasValue but got ${it.javaClass.simpleName}")
            value.comments()?.contains("is mutated from number to boolean") == true
        }.let {
            assertThat(it).hasSize(1)
        }

        negativePathPatterns.filter {
            val value = it as? HasValue ?: fail("Expected HasValue but got ${it.javaClass.simpleName}")
            value.comments()?.contains("is mutated from number to string") == true
        }.let {
            assertThat(it).hasSize(1)
        }

        assertThat(negativePathPatterns).allSatisfy {
            val value = it as? HasValue ?: fail("Expected HasValue but got ${it.javaClass.simpleName}")
            println(value.comments())
            assertThat(value.comments()).contains("PATH.id")
        }
    }

    @Test
    fun `should be able to match path parameter as pattern token with key when in mockMode`() {
        val urlPattern = buildHttpPathPattern("/pets/(id:number)")
        val path = "/pets/(id:number)"
        val result = urlPattern.matches(URI(path), Resolver(mockMode = true))

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should replace any non-encodable characters from values provided by StringProviders`() {
        val pattern = HttpPathPattern.from("/test/(userName:string)")
        val resolver = Resolver()
        val provider = object: StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>): String = "specmatic test"
        }

        StringProviders.with(provider) {
            val generated = pattern.generate(resolver)
            assertThat(generated).isEqualTo("/test/specmatic_test")
        }
    }

    @Test
    fun `should not encompass a path pattern which is structurally different`() {
        val simplestPathPattern = HttpPathPattern.from("/")
        val complexPathPattern = HttpPathPattern.from("/api/order/(param:integer)")

        assertThat(complexPathPattern.encompasses(simplestPathPattern, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
        assertThat(simplestPathPattern.encompasses(complexPathPattern, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Nested
    inner class FixValueTests {
        @Test
        fun `should regenerate path when segments size doesn't match`() {
            val urlPattern = buildHttpPathPattern(URI("/pets/123/owners"))
            val invalidPath = "/pets/123/owners/123"

            val fixedPath = urlPattern.fixValue(invalidPath, Resolver())
            println(fixedPath)

            assertThat(fixedPath).isEqualTo("/pets/123/owners")
        }

        @Test
        fun `should be able to fix invalid values in path parameters`() {
            val urlPattern = buildHttpPathPattern("/pets/(id:number)")
            val invalidPath = "/pets/abc"

            val dictionary = "PARAMETERS: { PATH: { id: 999 } }".let(Dictionary::fromYaml)
            val fixedPath = urlPattern.fixValue(invalidPath, Resolver(dictionary = dictionary))
            println(fixedPath)

            assertThat(fixedPath).isEqualTo("/pets/999")
        }

        @Test
        fun `should only add the prefix if the path already had it`() {
            val urlPattern = buildHttpPathPattern("/pets/(id:number)")
            val dictionary = "PARAMETERS: { PATH: { id: 999 } }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)

            val prefixedPath = "/pets/abc"
            val fixedPath = urlPattern.fixValue(prefixedPath, resolver)
            println(fixedPath)
            assertThat(fixedPath).isEqualTo("/pets/999")

            val unPrefixedPath = "pets/abc"
            val unPrefixedFixedPath = urlPattern.fixValue(unPrefixedPath, resolver)
            println(unPrefixedFixedPath)
            assertThat(unPrefixedFixedPath).isEqualTo("/pets/999")
        }

        @Test
        fun `should retain pattern token if it matches when resolver is in mock mode`() {
            val urlPattern = buildHttpPathPattern("/pets/(id:number)")
            val resolver = Resolver(mockMode = true)
            val validValue = "/pets/(id:number)"

            val fixedPath = urlPattern.fixValue(validValue, resolver)
            println(fixedPath)
            assertThat(fixedPath).isEqualTo(validValue)
        }

        @Test
        fun `should fix pattern token if it does not match`() {
            val urlPattern = buildHttpPathPattern("/pets/(id:number)")
            val dictionary = "PARAMETERS: { PATH: { id: 999 } }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary, mockMode = true)
            val validValue = "/pets/(id:string)"

            val fixedPath = urlPattern.fixValue(validValue, resolver)
            assertThat(fixedPath).isEqualTo("/pets/999")
        }

        @Test
        fun `should generate value when pattern token does not match when resolver is in mock mode`() {
            val urlPattern = buildHttpPathPattern("/pets/(id:number)")
            val dictionary = "PARAMETERS: { PATH: { id: 999 } }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary, mockMode = true)
            val validValue = "/pets/(string)"

            val fixedPath = urlPattern.fixValue(validValue, resolver)
            println(fixedPath)
            assertThat(fixedPath).isEqualTo("/pets/999")
        }

        @Test
        fun `should generate values even if pattern token matches but resolver is not in mock mode`() {
            val urlPattern = buildHttpPathPattern("/pets/(id:number)")
            val dictionary = "PARAMETERS: { PATH: { id: 999 } }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val validValue = "/pets/(number)"

            val fixedPath = urlPattern.fixValue(validValue, resolver)
            println(fixedPath)
            assertThat(fixedPath).isEqualTo("/pets/999")
        }

        @Test
        fun `should work when pattern-token contains key`() {
            val urlPattern = buildHttpPathPattern("/pets/(id:number)")
            val dictionary = "PARAMETERS: { PATH: { id: 999 } }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val validValue = "/pets/(id:number)"

            val fixedPath = urlPattern.fixValue(validValue, resolver)
            println(fixedPath)
            assertThat(fixedPath).isEqualTo("/pets/999")
        }

        @Test
        fun `should fix interpolated path parameter while preserving literal prefix`() {
            val urlPattern = buildHttpPathPattern("/product/product-(id:number)/latest")
            val dictionary = "PARAMETERS: { PATH: { id: 999 } }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val fixedPath = urlPattern.fixValue("/product/product-abc/latest", resolver)
            assertThat(fixedPath).isEqualTo("/product/product-999/latest")
        }
    }

    @Nested
    inner class FillInTheBlanksTests {
        @Test
        fun `should generate values for missing mandatory keys and pattern tokens`() {
            val pathPattern = buildHttpPathPattern("/pets/(id:number)/owners/(flag:boolean)")
            val path = "/pets/(number)/owners/(boolean)"
            val dictionary = "PARAMETERS: { PATH: { id: 999, flag: true } }".let(Dictionary::fromYaml)
            val filledPath = pathPattern.fillInTheBlanks(path, Resolver(dictionary = dictionary)).value

            assertThat(filledPath).isEqualTo("/pets/999/owners/true")
        }

        @Test
        fun `should handle any-value pattern token as a special case`() {
            val pathPattern = buildHttpPathPattern("/pets/(id:number)/owners/(flag:boolean)")
            val path = "/pets/(anyvalue)/owners/(boolean)"
            val dictionary = "PARAMETERS: { PATH: { id: 999, flag: true } }".let(Dictionary::fromYaml)
            val filledPath = pathPattern.fillInTheBlanks(path, Resolver(dictionary = dictionary)).value

            assertThat(filledPath).isEqualTo("/pets/999/owners/true")
        }

        @Test
        fun `should complain when pattern-token does not match the underlying pattern`() {
            val pathPattern = buildHttpPathPattern("/pets/(id:number)/owners/(flag:boolean)")
            val path = "/pets/(string)/owners/(boolean)"
            val dictionary = "PARAMETERS: { PATH: { id: 999, flag: true } }".let(Dictionary::fromYaml)
            val exception = assertThrows<ContractException> {
                pathPattern.fillInTheBlanks(path, Resolver(dictionary = dictionary)).value
            }

            assertThat(exception.failure().reportString()).isEqualToNormalizingWhitespace(
                toViolationReportString(
                    breadCrumb = "id",
                    details = DefaultMismatchMessages.patternMismatch("number", "string"),
                    StandardRuleViolation.TYPE_MISMATCH
                )
            )
        }

        @Test
        fun `should work when pattern-token contains key`() {
            val pathPattern = buildHttpPathPattern("/pets/(id:number)/owners/(flag:boolean)")
            val path = "/pets/(id:number)/owners/(flag:boolean)"
            val dictionary = "PARAMETERS: { PATH: { id: 999, flag: true } }".let(Dictionary::fromYaml)
            val filledPath = pathPattern.fillInTheBlanks(path, Resolver(dictionary = dictionary)).value

            assertThat(filledPath).isEqualTo("/pets/999/owners/true")
        }

        @Test
        fun `should fill interpolated path parameters and preserve literal prefixes`() {
            val pathPattern = buildHttpPathPattern("/product/product-(id:number)/order/order-(orderId:number)/latest")
            val path = "/product/product-(id:number)/order/order-(orderId:number)/latest"
            val dictionary = "PARAMETERS: { PATH: { id: 101, orderId: 202 } }".let(Dictionary::fromYaml)
            val filledPath = pathPattern.fillInTheBlanks(path, Resolver(dictionary = dictionary)).value
            assertThat(filledPath).isEqualTo("/product/product-101/order/order-202/latest")
        }
    }

    @Nested
    inner class InterpolatedPathParityTests {
        @Test
        fun `should generate interpolated path with inline delimiters using dictionary values`() {
            val pattern = buildHttpPathPattern("/test/(id1:string),(id2:string)/status")
            val dictionary = "PARAMETERS: { PATH: { id1: alpha, id2: beta } }".let(Dictionary::fromYaml)
            val generated = pattern.generate(Resolver(dictionary = dictionary))
            assertThat(generated).isEqualTo("/test/alpha,beta/status")
        }

        @Test
        fun `should validate interpolated path with inline delimiters with same behavior as normal paths`() {
            val pattern = buildHttpPathPattern("/test/(id1:string),(id2:number)/status")
            val success = pattern.matches("/test/alpha,42/status", Resolver())
            assertThat(success).isInstanceOf(Result.Success::class.java)

            val failure = pattern.matches("/test/alpha,not-a-number/status", Resolver())
            assertThat(failure).isInstanceOf(Result.Failure::class.java); failure as Result.Failure
            assertThat(failure.failureReason).isEqualTo(FailureReason.URLPathParamMismatchButSameStructure)

            val details = failure.toMatchFailureDetails()
            assertThat(details.breadCrumbs).containsExactly("PARAMETERS.PATH", "id2")
            assertThat(details.errorMessages).hasSize(1)
            assertThat(details.errorMessages.single()).isEqualToNormalizingWhitespace("""Expected type number, actual was value "not-a-number" of type string""")
        }

        @Test
        fun `should fill in the blanks for interpolated path with inline delimiters`() {
            val pathPattern = buildHttpPathPattern("/test/(id1:number),(id2:number)/status")
            val dictionary = "PARAMETERS: { PATH: { id1: 101, id2: 202 } }".let(Dictionary::fromYaml)
            val filledPath = pathPattern.fillInTheBlanks("/test/(id1:number),(id2:number)/status", Resolver(dictionary = dictionary)).value
            assertThat(filledPath).isEqualTo("/test/101,202/status")
        }

        @Test
        fun `should fix interpolated path parameters while preserving inline delimiters`() {
            val pathPattern = buildHttpPathPattern("/test/(id1:number),(id2:number)/status")
            val dictionary = "PARAMETERS: { PATH: { id1: 101, id2: 202 } }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val fixedPath = pathPattern.fixValue("/test/abc,xyz/status", resolver)
            assertThat(fixedPath).isEqualTo("/test/101,202/status")
        }

        @Test
        fun `should support newBasedOn for interpolated paths`() {
            val pathPattern = buildHttpPathPattern("/product/product-(id:number)/order/order-(orderId:number)/latest")
            val resolver = Resolver(mapOf("id" to NumberValue(11), "orderId" to NumberValue(22)))
            val generatedPathSegments = pathPattern.newBasedOn(Row(), resolver).first()
            val path = generatedPathSegments.joinToString("") { it.generate(resolver).toStringLiteral() }
            assertThat(path).isEqualTo("/product/product-11/order/order-22/latest")
        }

        @Test
        fun `should expand interpolated newBasedOn when one path parameter is enum`() {
            val enumPathSegments = pathToPattern("/test/(id:number)-(status:string)/latest").map {
                if (it.key == "status") {
                    it.copy(pattern = EnumPattern(listOf(StringValue("active"), StringValue("inactive"))))
                } else {
                    it
                }
            }

            val pathPattern = HttpPathPattern(pathSegmentPatterns = enumPathSegments, path = "/test/(id:number)-(status:string)/latest")
            val generatedPaths = pathPattern.newBasedOn(Row(mapOf("id" to "17")), Resolver()).map { generatedPathSegments ->
                generatedPathSegments.joinToString("") { it.generate(Resolver()).toStringLiteral() }
            }.toList()

            assertThat(generatedPaths).containsExactlyInAnyOrder("/test/17-active/latest", "/test/17-inactive/latest")
        }

        @Test
        fun `should generate negatives for interpolated paths`() {
            val pattern = buildHttpPathPattern("/product/product-(id:number)/order/order-(orderId:number)/latest")
            val negatives = pattern.negativeBasedOn(Row(), Resolver()).toList()

            assertThat(negatives).hasSize(4)
            assertThat(negatives[0].value).containsExactlyElementsOf(
                listOf(
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/product/product-"))),
                    URLPathSegmentPattern(BooleanPattern(), "id"),
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/order/order-"))),
                    URLPathSegmentPattern(NumberPattern(), "orderId"),
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/latest")))
                )
            )
            assertThat(negatives[1].value).containsExactlyElementsOf(
                listOf(
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/product/product-"))),
                    URLPathSegmentPattern(StringPattern(), "id"),
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/order/order-"))),
                    URLPathSegmentPattern(NumberPattern(), "orderId"),
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/latest")))
                )
            )
            assertThat(negatives[2].value).containsExactlyElementsOf(
                listOf(
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/product/product-"))),
                    URLPathSegmentPattern(NumberPattern(), "id"),
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/order/order-"))),
                    URLPathSegmentPattern(BooleanPattern(), "orderId"),
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/latest")))
                )
            )
            assertThat(negatives[3].value).containsExactlyElementsOf(
                listOf(
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/product/product-"))),
                    URLPathSegmentPattern(NumberPattern(), "id"),
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/order/order-"))),
                    URLPathSegmentPattern(StringPattern(), "orderId"),
                    URLPathSegmentPattern(ExactValuePattern(StringValue("/latest")))
                )
            )
        }

        @Test
        fun `should support backward compatibility checks for interpolated paths using encompasses`() {
            val provider = buildHttpPathPattern("/product/product-(id:string)/order/order-(orderId:string)/latest")
            val consumer = buildHttpPathPattern("/product/product-(id:number)/order/order-(orderId:number)/latest")
            val result = provider.encompasses(consumer, Resolver(), Resolver())
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `should fail backward compatibility for interpolated path when literals match but parameter pattern is incompatible`() {
            val provider = buildHttpPathPattern("/test/(id:number)-(status:string)/latest")
            val consumer = buildHttpPathPattern("/test/(id:string)-(status:string)/latest")

            val result = provider.encompasses(consumer, Resolver(), Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)

            val details = (result as Result.Failure).toMatchFailureDetails()
            assertThat(details.breadCrumbs).isEmpty()
            assertThat(details.errorMessages).hasSize(1)
            assertThat(details.errorMessages.single()).isEqualToNormalizingWhitespace("""Expected number, actual was string""")
        }

        @Test
        fun `should fail backward compatibility checks when interpolated path structure differs`() {
            val provider = buildHttpPathPattern("/product/product-(id:string)/order/order-(orderId:string)/latest")
            val consumer = buildHttpPathPattern("/product/(id:string)/order/(orderId:string)/latest")

            val result = provider.encompasses(consumer, Resolver(), Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)

            val details = (result as Result.Failure).toMatchFailureDetails()
            assertThat(details.breadCrumbs).isEmpty()
            assertThat(details.errorMessages).hasSize(1)
            assertThat(details.errorMessages[0]).isEqualToNormalizingWhitespace("""Expected "/product/product-", actual was "/product/"""")
        }

        @Test
        fun `should match interpolated path parameter as pattern token with resolver in mockMode`() {
            val pattern = buildHttpPathPattern("/product/product-(id:number)/latest")
            val result = pattern.matches(URI("/product/product-(id:number)/latest"), Resolver(mockMode = true))
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `should not match interpolated path parameter as pattern token with resolver when not in mockMode`() {
            val pattern = buildHttpPathPattern("/product/product-(id:number)/latest")

            val result = pattern.matches(URI("/product/product-(id:number)/latest"), Resolver(mockMode = false))
            assertThat(result).isInstanceOf(Result.Failure::class.java)

            val details = (result as Result.Failure).toMatchFailureDetails()
            assertThat(details.breadCrumbs).containsExactly("PARAMETERS.PATH", "id")
            assertThat(details.errorMessages).hasSize(1)
            assertThat(details.errorMessages.single()).isEqualToNormalizingWhitespace("""Expected type number, actual was value "(number)" of type string""")
        }

        @Test
        fun `should fix interpolated path when pattern tokens contain keys`() {
            val pathPattern = buildHttpPathPattern("/product/product-(id:number)/order/order-(orderId:number)/latest")
            val dictionary = "PARAMETERS: { PATH: { id: 101, orderId: 202 } }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val fixedPath = pathPattern.fixValue("/product/product-(id:number)/order/order-(orderId:number)/latest", resolver)
            assertThat(fixedPath).isEqualTo("/product/product-101/order/order-202/latest")
        }

        @Test
        fun `should fix interpolated path from string token to number using dictionary value`() {
            val pathPattern = buildHttpPathPattern("/product/product-(id:number)/latest")
            val dictionary = "PARAMETERS: { PATH: { id: 101 } }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val fixedPath = pathPattern.fixValue("/product/product-(string)/latest", resolver)
            assertThat(fixedPath).isEqualTo("/product/product-101/latest")
        }

        @Test
        fun `should fill interpolated path when pattern tokens do not contain keys`() {
            val pathPattern = buildHttpPathPattern("/product/product-(id:number)/order/order-(orderId:number)/latest")
            val dictionary = "PARAMETERS: { PATH: { id: 101, orderId: 202 } }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val filledPath = pathPattern.fillInTheBlanks("/product/product-(number)/order/order-(number)/latest", resolver).value
            assertThat(filledPath).isEqualTo("/product/product-101/order/order-202/latest")
        }

        @Test
        fun `should retain interpolated pattern tokens when they match in mockMode`() {
            val pathPattern = buildHttpPathPattern("/product/product-(id:number)/order/order-(orderId:number)/latest")
            val resolver = Resolver(mockMode = true)
            val pathWithTokens = "/product/product-(id:number)/order/order-(orderId:number)/latest"
            val fixedPath = pathPattern.fixValue(pathWithTokens, resolver)
            assertThat(fixedPath).isEqualTo(pathWithTokens)
        }

        @Test
        fun `should fill interpolated path for pattern tokens`() {
            val pathPattern = buildHttpPathPattern("/product/product-(id:number)/order/order-(orderId:number)/latest")
            val dictionary = "PARAMETERS: { PATH: { id: 101, orderId: 202 } }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val pathWithTokens = "/product/product-(anyvalue)/order/order-(anyvalue)/latest"
            val filledPath = pathPattern.fillInTheBlanks(pathWithTokens, resolver).value
            assertThat(filledPath).isEqualTo("/product/product-101/order/order-202/latest")
        }

        @Test
        fun `should support newBasedOn for interpolated paths using row values`() {
            val pathPattern = buildHttpPathPattern("/product/product-(id:number)/order/order-(orderId:number)/latest")
            val row = Row(mapOf("id" to "31", "orderId" to "41"))
            val generatedPathSegments = pathPattern.newBasedOn(row, Resolver()).first()
            val path = generatedPathSegments.joinToString("") { it.generate(Resolver()).toStringLiteral() }
            assertThat(path).isEqualTo("/product/product-31/order/order-41/latest")
        }

        @Test
        fun `should generate negative interpolated path params with annotations`() {
            val pathPattern = buildHttpPathPattern("/product/product-(id:number)/order/order-(orderId:number)/latest")
            val negatives = pathPattern.negativeBasedOn(Row(mapOf("id" to "10", "orderId" to "20")), Resolver()).toList()

            assertThat(negatives).hasSize(4)
            val comments = negatives.map {
                val value = it as? HasValue ?: fail("Expected HasValue but got ${it.javaClass.simpleName}")
                value.comments().orEmpty()
            }

            assertThat(comments.count { it.contains("PATH.id") }).isEqualTo(2)
            assertThat(comments.count { it.contains("PATH.orderId") }).isEqualTo(2)
            assertThat(comments.count { it.contains("is mutated from number to boolean") }).isEqualTo(2)
            assertThat(comments.count { it.contains("is mutated from number to string") }).isEqualTo(2)
            assertThat(comments).allSatisfy {
                assertThat(it).contains("PATH.")
                assertThat(it).contains("is mutated from number to")
            }
        }

        @Test
        fun `should update interpolated path parameter directly for inline delimiters`() {
            val pathPattern = buildHttpPathPattern("/test/(id1:string),(id2:string)/status")
            val updated = pathPattern.updatePathParameter("/test/first,second/status", "id2", StringValue("updated"))
            assertThat(updated).isEqualTo("/test/first,updated/status")
        }

        @Test
        fun `should update interpolated path parameter directly for segment delimiters`() {
            val pathPattern = buildHttpPathPattern("/test/(id1:string)/(id2:string)/status")
            val updated = pathPattern.updatePathParameter("/test/first/second/status", "id1", StringValue("updated"))
            assertThat(updated).isEqualTo("/test/updated/second/status")
        }
    }
}
