package io.specmatic.core

import io.specmatic.GENERATION
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.*
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
        val pattern = HttpPathPattern(listOf(
            URLPathSegmentPattern(StringPattern(), "first", conflicts = setOf("a")),
            URLPathSegmentPattern(StringPattern(), "second", conflicts = setOf("b")),
            URLPathSegmentPattern(StringPattern(), "third", conflicts = setOf("c"))
        ), path = "/(first:String)/(second:String)/(third:String)")
        val result = pattern.matches(path, Resolver())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should match not path when structure does matches and there are some segment conflicts`() {
        val pattern = HttpPathPattern(listOf(
            URLPathSegmentPattern(NumberPattern(), "first", conflicts = setOf("a")),
            URLPathSegmentPattern(StringPattern(), "second", conflicts = setOf("b")),
            URLPathSegmentPattern(StringPattern(), "third", conflicts = setOf("c"))
        ), path = "/(first:number)/(second:String)/(third:String)")
        val result = pattern.matches("/a/b/1", Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should return failure when path conflicts with an existing path even if structure and data type matches`() {
        val pattern = HttpPathPattern(listOf(
            URLPathSegmentPattern(StringPattern(), "first", conflicts = setOf("a")),
            URLPathSegmentPattern(StringPattern(), "second", conflicts = setOf("b")),
            URLPathSegmentPattern(StringPattern(), "third", conflicts = setOf("c"))
        ), path = "/(first:String)/(second:String)/(third:String)")
        val result = pattern.matches("a/b/c", Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).hasReason(FailureReason.URLPathParamMatchButConflict)).isTrue()
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        >> PATH
        Path segments of URL a/b/c overlap with another URL that has the same structure
        >> PARAMETERS.PATH.first
        Value "a" conflicts with an existing path using the same prefix
        >> PARAMETERS.PATH.second
        Value "b" conflicts with an existing path using the same prefix
        >> PARAMETERS.PATH.third
        Value "c" conflicts with an existing path using the same prefix
        """.trimIndent())
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
                    listOf("""Expected "pets", actual was "owners"""")
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
                    ExactValuePattern(StringValue("pets")),
                    any()
                )
            } returns StringValue("pets")
            every {
                it.withCyclePrevention<NumberValue>(
                    DeferredPattern("(number)", "petid"),
                    any()
                )
            } returns NumberValue(123)
            every {
                it.withCyclePrevention<StringValue>(
                    ExactValuePattern(StringValue("owner")),
                    any()
                )
            } returns StringValue("owner")
            every {
                it.withCyclePrevention<StringValue>(
                    DeferredPattern("(string)", "owner"),
                    any()
                )
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
        val path = urlPathSegmentPatterns.joinToString("/") { it.generate(resolver).toStringLiteral() }
        assertEquals("pets/10", path)
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
                URLPathSegmentPattern(ExactValuePattern(StringValue("products"))),
                URLPathSegmentPattern(BooleanPattern(), "id"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("image"))),
            )
        )
        assertThat(negativePatterns[1].value).containsExactlyElementsOf(
            listOf(
                URLPathSegmentPattern(ExactValuePattern(StringValue("products"))),
                URLPathSegmentPattern(StringPattern(), "id"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("image"))),
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
                URLPathSegmentPattern(ExactValuePattern(StringValue("organizations"))),
                URLPathSegmentPattern(BooleanPattern(), "orgId"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("employees"))),
                URLPathSegmentPattern(DeferredPattern("(number)"), "empId"),
            )
        )
        assertThat(negativePatterns[1].value).containsExactlyElementsOf(
            listOf(
                URLPathSegmentPattern(ExactValuePattern(StringValue("organizations"))),
                URLPathSegmentPattern(StringPattern(), "orgId"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("employees"))),
                URLPathSegmentPattern(DeferredPattern("(number)"), "empId"),
            )
        )
        assertThat(negativePatterns[2].value).containsExactlyElementsOf(
            listOf(
                URLPathSegmentPattern(ExactValuePattern(StringValue("organizations"))),
                URLPathSegmentPattern(DeferredPattern("(number)"), "orgId"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("employees"))),
                URLPathSegmentPattern(BooleanPattern(), "empId"),
            )
        )
        assertThat(negativePatterns[3].value).containsExactlyElementsOf(
            listOf(
                URLPathSegmentPattern(ExactValuePattern(StringValue("organizations"))),
                URLPathSegmentPattern(DeferredPattern("(number)"), "orgId"),
                URLPathSegmentPattern(ExactValuePattern(StringValue("employees"))),
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
                URLPathSegmentPattern(ExactValuePattern(StringValue("pets"))),
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
            value.comments()?.contains("mutated to boolean") == true
        }.let {
            assertThat(it).hasSize(1)
        }

        negativePathPatterns.filter {
            val value = it as? HasValue ?: fail("Expected HasValue but got ${it.javaClass.simpleName}")
            value.comments()?.contains("mutated to string") == true
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
            assertThat(unPrefixedFixedPath).isEqualTo("pets/999")
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

            assertThat(exception.failure().reportString()).isEqualToNormalizingWhitespace("""
            >> id
            Expected number, actual was string
            """.trimIndent())
        }

        @Test
        fun `should work when pattern-token contains key`() {
            val pathPattern = buildHttpPathPattern("/pets/(id:number)/owners/(flag:boolean)")
            val path = "/pets/(id:number)/owners/(flag:boolean)"
            val dictionary = "PARAMETERS: { PATH: { id: 999, flag: true } }".let(Dictionary::fromYaml)
            val filledPath = pathPattern.fillInTheBlanks(path, Resolver(dictionary = dictionary)).value

            assertThat(filledPath).isEqualTo("/pets/999/owners/true")
        }
    }
}
