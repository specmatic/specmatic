package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class BreadCrumbToJsonPathConverterTest {
    private val converter = BreadCrumbToJsonPathConverter()

    @ParameterizedTest
    @MethodSource("standardPathProvider")
    fun `should convert standard breadcrumbs to json path`(breadcrumbs: List<String>, expectedPath: String) {
        val result = converter.toJsonPath(breadcrumbs)
        assertThat(result).isEqualTo(expectedPath)
    }

    @Test
    fun `should handle array indices by converting brackets to slashes`() {
        val breadcrumbs = listOf("RESPONSE", "BODY", "items", "[0]", "id")
        assertThat(converter.toJsonPath(breadcrumbs)).isEqualTo("/http-response/body/items/0/id")
        assertThat(converter.convert(breadcrumbs)).isEqualTo(listOf("http-response", "body", "items", "0", "id"))
    }

    @Test
    fun `should handle array indices by converting brackets to slashes when combined with a key`() {
        val breadcrumbs = listOf("RESPONSE", "BODY", "items[0]", "details", "aliases[1]")
        assertThat(converter.toJsonPath(breadcrumbs)).isEqualTo("/http-response/body/items/0/details/aliases/1")
        assertThat(converter.convert(breadcrumbs)).isEqualTo(listOf("http-response", "body", "items", "0", "details", "aliases", "1"))
    }

    @Test
    fun `should filter out tilde breadcrumbs from the final path`() {
        // Tilde breadcrumbs like (~~~ ABC) are used for descriptions but should not appear in the JSON path
        val breadcrumbs = listOf(
            "RESPONSE",
            "BODY",
            "(~~~ OneOf Option)",
            "person",
            "(~~~ AnyOf Option)",
            "name"
        )

        val result = converter.toJsonPath(breadcrumbs)
        assertThat(result).isEqualTo("/http-response/body/person/name")
    }

    @Test
    fun `should handle empty or blank breadcrumbs`() {
        val breadcrumbs = listOf("RESPONSE", "", "   ", "BODY", "name")
        val result = converter.toJsonPath(breadcrumbs)
        assertThat(result).isEqualTo("/http-response/body/name")
    }

    @Test
    fun `should return empty path for empty breadcrumb list`() {
        val result = converter.toJsonPath(emptyList())
        assertThat(result).isEqualTo("/")
    }

    @Test
    fun `should support custom transformation configuration`() {
        val customConfig = TransformationConfig(
            transformations = listOf(
                TransformationStrategy.DirectReplacement("CUSTOM", "replaced"),
                TransformationStrategy.DirectReplacement(".", "/")
            )
        )

        val customConverter = BreadCrumbToJsonPathConverter(customConfig)
        val breadcrumbs = listOf("CUSTOM", "path", "value")
        val result = customConverter.toJsonPath(breadcrumbs)

        assertThat(result).isEqualTo("/replaced/path/value")
    }

    @Test
    fun `should trim leading slashes from individual components`() {
        val customConfig = TransformationConfig(
            transformations = listOf(
                TransformationStrategy.DirectReplacement("SLASHY", "/starts-with-slash")
            )
        )

        val customConverter = BreadCrumbToJsonPathConverter(customConfig)
        val breadcrumbs = listOf("SLASHY", "end")
        val result = customConverter.toJsonPath(breadcrumbs)

        assertThat(result).isEqualTo("/starts-with-slash/end")
    }

    @Test
    fun `should not rewrite header keys that contain REQUEST as a substring`() {
        val breadcrumbs = listOf("REQUEST", "HEADER", "X-Request-ID")
        val result = converter.toJsonPath(breadcrumbs)
        assertThat(result).isEqualTo("/http-request/header/X-Request-ID")
    }

    companion object {
        @JvmStatic
        fun standardPathProvider(): Stream<Arguments> {
            return Stream.of(
                // -- Request / Response Bodies --
                Arguments.of(
                    listOf("RESPONSE", "BODY", "name"),
                    "/http-response/body/name"
                ),
                Arguments.of(
                    listOf("REQUEST", "BODY", "address", "zipcode"),
                    "/http-request/body/address/zipcode"
                ),

                // -- Array Indices --
                Arguments.of(
                    listOf("RESPONSE", "BODY", "list", "[10]"),
                    "/http-response/body/list/10"
                ),
                Arguments.of(
                    listOf("RESPONSE", "BODY", "users", "[0]", "roles", "[5]", "id"),
                    "/http-response/body/users/0/roles/5/id"
                ),

                // -- Header Parameters --
                Arguments.of(
                    listOf("REQUEST", "HEADER", "Authorization"),
                    "/http-request/header/Authorization"
                ),
                Arguments.of(
                    listOf("RESPONSE", "HEADER", "Content-Type"),
                    "/http-response/header/Content-Type"
                ),
                Arguments.of(
                    listOf("REQUEST", "PARAMETERS.HEADER", "X-Request-ID"),
                    "/http-request/header/X-Request-ID"
                ),

                // -- Path Parameters --
                Arguments.of(
                    listOf("REQUEST", "PATH", "userId"),
                    "/http-request/path/userId"
                ),
                Arguments.of(
                    listOf("REQUEST", "PARAMETERS.PATH", "orderId"),
                    "/http-request/path/orderId"
                ),

                // -- Query Parameters --
                Arguments.of(
                    listOf("REQUEST", "QUERY", "search"),
                    "/http-request/query/search"
                ),
                Arguments.of(
                    listOf("REQUEST", "PARAMETERS.QUERY", "page"),
                    "/http-request/query/page"
                ),

                // -- Tilde and Other Transformations --
                Arguments.of(
                    listOf("RESPONSE", "BODY", "(~~~ OneOf)", "person", "(~~~ AnyOf)", "name"),
                    "/http-response/body/person/name"
                ),
                Arguments.of(
                    listOf("RESPONSE", "BODY", "data", "[0]", "attributes.priority"),
                    "/http-response/body/data/0/attributes/priority"
                ),
                Arguments.of(
                    listOf("RESPONSE", "BODY", "nested.object.structure"),
                    "/http-response/body/nested/object/structure"
                ),

                // -- Multi-component entries in a single list element (dot-splitting) --
                Arguments.of(
                    listOf("RESPONSE.STATUS"),
                    "/http-response/status"
                ),
                Arguments.of(
                    listOf("REQUEST.BODY.name.key"),
                    "/http-request/body/name/key"
                ),
                Arguments.of(
                    listOf("REQUEST", "BODY.name.key"),
                    "/http-request/body/name/key"
                ),
                Arguments.of(
                    listOf("REQUEST.BODY", "name.key"),
                    "/http-request/body/name/key"
                ),
                Arguments.of(
                    listOf("RESPONSE.HEADER.Content-Type"),
                    "/http-response/header/Content-Type"
                ),
                Arguments.of(
                    listOf("REQUEST.PARAMETERS.HEADER.X-Request-ID"),
                    "/http-request/header/X-Request-ID"
                ),

                // -- Regression: structural token must not rewrite inside header keys --
                Arguments.of(
                    listOf("REQUEST", "HEADER", "X-Request-ID"),
                    "/http-request/header/X-Request-ID"
                ),
                Arguments.of(
                    listOf("REQUEST", "HEADER", "X-REQUEST-ID"),
                    "/http-request/header/X-REQUEST-ID"
                ),
                Arguments.of(
                    listOf("REQUEST", "HEADER", "X-http-request-ID"), // already contains "http-request" text
                    "/http-request/header/X-http-request-ID"
                ),

                // -- Extra edge cases around dot splitting and blanks --
                Arguments.of(
                    listOf("RESPONSE..BODY...name"), // empty segments should be dropped
                    "/http-response/body/name"
                ),
                Arguments.of(
                    listOf("RESPONSE", "BODY", "attributes..priority"),
                    "/http-response/body/attributes/priority"
                ),
                Arguments.of(
                    listOf("RESPONSE", "BODY", "attributes[0]"),
                    "/http-response/body/attributes/0"
                ),

                // -- Mix dot-splitting with indices (index token still separate) --
                Arguments.of(
                    listOf("RESPONSE.BODY.items", "[0]", "id"),
                    "/http-response/body/items/0/id"
                ),
                Arguments.of(
                    listOf("RESPONSE", "BODY.items", "[10]"),
                    "/http-response/body/items/10"
                ),

                )
        }
    }
}
