package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI

class PathSegmentExtractorTest {
    @Nested
    inner class NormalizationTests {
        @ParameterizedTest(name = "contract \"{0}\", input \"{1}\" -> \"{2}\"")
        @MethodSource("io.specmatic.core.PathSegmentExtractorTest#normalizationCases")
        fun `ensurePrefixAndSuffix should normalize based on contract path`(contractPath: String, rawPath: String, expected: String) {
            val normalized = extractor(contractPath).ensurePrefixAndSuffix(rawPath)
            assertThat(normalized).isEqualTo(expected)
        }
    }

    @Nested
    inner class ExtractionTests {
        @Test
        fun `should extract pure literals deterministically`() {
            val extractor = extractorForContract("/pets/list")
            val segments = extractor.extract("pets/list")
            assertThat(segments).containsExactly("/pets/list")
        }

        @Test
        fun `should extract literal variable literal variable path`() {
            val extractor = extractorForContract("/pets/(id:string)/owners/(owner:string)")
            val segments = extractor.extract("/pets/123/owners/alice")
            assertThat(segments).containsExactly("/pets/", "123", "/owners/", "alice")
        }

        @Test
        fun `should extract when variable appears at start and end`() {
            val extractor = extractorForContract("/(tenant:string)/orders/(orderId:string)")
            val segments = extractor.extract("/acme/orders/42")
            assertThat(segments).containsExactly("/", "acme", "/orders/", "42")
        }

        @Test
        fun `should honor trailing slash normalization for extraction`() {
            val extractor = extractorForContract("/pets/(id:string)/")
            val segments = extractor.extract("/pets/123")
            assertThat(segments).containsExactly("/pets/", "123", "/")
        }

        @Test
        fun `should extract slash separated continuous parameters`() {
            val extractor = extractorForContract("/test/(id1:string)/(id2:string)/status")
            val segments = extractor.extract("/test/first/second/status")
            assertThat(segments).containsExactly("/test/", "first", "/", "second", "/status")
        }
    }

    @Nested
    inner class InterpolatedExtractionTests {
        @Test
        fun `should extract hyphen interpolated path`() {
            val extractor = extractorForContract("/product/product-(id:string)/order/order-(orderId:string)/latest")
            val segments = extractor.extract("/product/product-12/order/order-abc/latest")
            assertThat(segments).containsExactly("/product/product-", "12", "/order/order-", "abc", "/latest")
        }

        @Test
        fun `should extract comma separated interpolated path`() {
            val extractor = extractorForContract("/test/(id1:string),(id2:string)/status")
            val segments = extractor.extract("/test/first,second/status")
            assertThat(segments).containsExactly("/test/", "first", ",", "second", "/status")
        }

        @Test
        fun `should extract mixed literals around variables in the same segment`() {
            val extractor = extractorForContract("/x-(a:string)-y-(b:string).json")
            val segments = extractor.extract("/x-10-y-20.json")
            assertThat(segments).containsExactly("/x-", "10", "-y-", "20", ".json")
        }

        @Test
        fun `should extract repeated separator boundaries`() {
            val extractor = extractorForContract("/r--(left:string)--(right:string)--end")
            val segments = extractor.extract("/r--A--B--end")
            assertThat(segments).containsExactly("/r--", "A", "--", "B", "--end")
        }
    }

    @Nested
    inner class MismatchBehaviorTests {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.PathSegmentExtractorTest#mismatchCases")
        fun `should partition mismatched input deterministically`(description: String, extractor: PathSegmentExtractor, rawPath: String, expectedSegments: List<String>) {
            val segments = extractor.extract(rawPath)
            assertThat(segments).containsExactlyElementsOf(expectedSegments)
            assertThat(segments.joinToString("")).isEqualTo(extractor.ensurePrefixAndSuffix(rawPath))
            assertThat(segments).doesNotContain("")
        }
    }

    @Nested
    inner class IntegrationParityTests {
        @Test
        fun `should align extractor output with interpolated hyphen path and match successfully`() {
            val contractPath = "/product/product-(id:string)/order/order-(orderId:string)/latest"
            val pathPattern = HttpPathPattern.from(contractPath)
            val extractor = extractorForContract(contractPath)
            val rawPath = "/product/product-12/order/order-abc/latest"
            val segments = extractor.extract(rawPath)
            assertThat(segments).containsExactly("/product/product-", "12", "/order/order-", "abc", "/latest")
            assertThat(pathPattern.matches(URI(rawPath), Resolver())).isInstanceOf(Result.Success::class.java)
            assertThat(pathPattern.extractPathParams(rawPath, Resolver())).isEqualTo(mapOf("id" to "12", "orderId" to "abc"))
        }

        @Test
        fun `should extract comma separated interpolated params via HttpPathPattern`() {
            val contractPath = "/test/(id1:string),(id2:string)/status"
            val pathPattern = HttpPathPattern.from(contractPath)
            val extractor = extractorForContract(contractPath)
            val rawPath = "/test/first,second/status"
            val segments = extractor.extract(rawPath)
            assertThat(segments.joinToString("")).isEqualTo(extractor.ensurePrefixAndSuffix(rawPath))
            assertThat(pathPattern.matches(URI(rawPath), Resolver())).isInstanceOf(Result.Success::class.java)
            assertThat(pathPattern.extractPathParams(rawPath, Resolver())).isEqualTo(mapOf("id1" to "first", "id2" to "second"))
        }

        @Test
        fun `should extract slash separated interpolated params via HttpPathPattern`() {
            val contractPath = "/test/(id1:string)/(id2:string)/status"
            val pathPattern = HttpPathPattern.from(contractPath)
            val extractor = extractorForContract(contractPath)
            val rawPath = "/test/first/second/status"
            val segments = extractor.extract(rawPath)
            assertThat(segments.joinToString("")).isEqualTo(extractor.ensurePrefixAndSuffix(rawPath))
            assertThat(pathPattern.matches(URI(rawPath), Resolver())).isInstanceOf(Result.Success::class.java)
            assertThat(pathPattern.extractPathParams(rawPath, Resolver())).isEqualTo(mapOf("id1" to "first", "id2" to "second"))
        }

        @Test
        fun `should still report HttpPathPattern mismatch when extractor partitions mismatched input`() {
            val contractPath = "/product/product-(id:string)/order/order-(orderId:string)/latest"
            val pathPattern = HttpPathPattern.from(contractPath)
            val extractor = extractorForContract(contractPath)
            val rawPath = "/product/12/order/abc/latest"
            val segments = extractor.extract(rawPath)
            val result = pathPattern.matches(URI(rawPath), Resolver())
            assertThat(segments.joinToString("")).isEqualTo(extractor.ensurePrefixAndSuffix(rawPath))
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat((result as Result.Failure).failureReason).isEqualTo(FailureReason.URLPathMisMatch)
        }
    }

    private fun extractorForContract(contractPath: String): PathSegmentExtractor = PathSegmentExtractor(contractPath, pathToPattern(contractPath))
    private fun extractor(contractPath: String, vararg segments: URLPathSegmentPattern): PathSegmentExtractor = PathSegmentExtractor(contractPath, segments.toList())

    companion object {
        @JvmStatic
        fun normalizationCases(): List<Arguments> = listOf(
            Arguments.of("/pets/list", "pets/list", "/pets/list"),
            Arguments.of("pets/list", "/pets/list", "pets/list"),
            Arguments.of("/pets/list/", "/pets/list", "/pets/list/"),
            Arguments.of("/pets/list", "/pets/list/", "/pets/list"),
            Arguments.of("/pets/list", "///pets//list", "/pets/list"),
            Arguments.of("/pets/list/", "pets///list", "/pets/list/"),
            Arguments.of("/", "", "/"),
            Arguments.of("/", "orders", "/orders")
        )

        @JvmStatic
        fun mismatchCases(): List<Arguments> = listOf(
            Arguments.of(
                "literal mismatch at beginning",
                PathSegmentExtractor(contractPath = "/pets/(id:string)", pathSegmentPatterns = pathToPattern("/pets/(id:string)")),
                "/dogs/123",
                listOf("/dogs/", "123")
            ),
            Arguments.of(
                "literal mismatch in middle",
                PathSegmentExtractor(contractPath = "/pets/(id:string)/owners/", pathSegmentPatterns = pathToPattern("/pets/(id:string)/owners/")),
                "/pets/123/users",
                listOf("/pets/", "123", "/users/")
            ),
            Arguments.of(
                "missing expected literal suffix",
                PathSegmentExtractor(contractPath = "/orders/(id:string)/status", pathSegmentPatterns = pathToPattern("/orders/(id:string)/status")),
                "/orders/10",
                listOf("/orders/", "10")
            ),
            Arguments.of(
                "path with extra trailing content",
                PathSegmentExtractor(contractPath = "/pets/(id:string)", pathSegmentPatterns = pathToPattern("/pets/(id:string)")),
                "/pets/123/owners/alice",
                listOf("/pets/", "123", "/owners/alice")
            ),
        )
    }
}
