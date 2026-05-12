package io.specmatic.conversions

import io.specmatic.conversions.YamlNodeKind.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JsonPointerSourceMapTest {
    @Test
    fun `empty yaml produces empty map`() {
        val map = JsonPointerSourceMap("").build()

        assertThat(map).isEqualTo(emptyMap<String, YamlNodeLocation>())
    }

    @Test
    fun `root scalar maps the empty pointer to the scalar`() {
        val map = JsonPointerSourceMap("hello").build()

        val expected = mapOf(
            "" to YamlNodeLocation(1, 1, SCALAR),
        )
        assertThat(map).isEqualTo(expected)
    }

    @Test
    fun `child entries are positioned at their introducing key`() {
        val yaml = """
            openapi: 3.0.0
            info:
              title: Pets
        """.trimIndent()

        val map = JsonPointerSourceMap(yaml).build()

        val expected = mapOf(
            "" to YamlNodeLocation(1, 1, MAPPING),
            "/openapi" to YamlNodeLocation(1, 1, SCALAR),
            "/info" to YamlNodeLocation(2, 1, MAPPING),
            "/info/title" to YamlNodeLocation(3, 3, SCALAR),
        )
        assertThat(map).isEqualTo(expected)
    }

    @Test
    fun `sequence elements are indexed numerically`() {
        val yaml = """
            servers:
              - url: https://a
              - url: https://b
        """.trimIndent()

        val map = JsonPointerSourceMap(yaml).build()

        val expected = mapOf(
            "" to YamlNodeLocation(1, 1, MAPPING),
            "/servers" to YamlNodeLocation(1, 1, SEQUENCE),
            "/servers/0" to YamlNodeLocation(2, 5, MAPPING),
            "/servers/0/url" to YamlNodeLocation(2, 5, SCALAR),
            "/servers/1" to YamlNodeLocation(3, 5, MAPPING),
            "/servers/1/url" to YamlNodeLocation(3, 5, SCALAR),
        )
        assertThat(map).isEqualTo(expected)
    }

    @Test
    fun `slashes in keys are escaped per RFC 6901`() {
        val yaml = """
            paths:
              /pets:
                get:
                  summary: list
        """.trimIndent()

        val map = JsonPointerSourceMap(yaml).build()

        val expected = mapOf(
            "" to YamlNodeLocation(1, 1, MAPPING),
            "/paths" to YamlNodeLocation(1, 1, MAPPING),
            "/paths/~1pets" to YamlNodeLocation(2, 3, MAPPING),
            "/paths/~1pets/get" to YamlNodeLocation(3, 5, MAPPING),
            "/paths/~1pets/get/summary" to YamlNodeLocation(4, 7, SCALAR),
        )
        assertThat(map).isEqualTo(expected)
    }

    @Test
    fun `tildes in keys are escaped as tilde-zero`() {
        val map = JsonPointerSourceMap("a~b: 1").build()

        val expected = mapOf(
            "" to YamlNodeLocation(1, 1, MAPPING),
            "/a~0b" to YamlNodeLocation(1, 1, SCALAR),
        )
        assertThat(map).isEqualTo(expected)
    }

    @Test
    fun `realistic openapi spec with inline schemas has no refTargets and indexes every node`() {
        val map = JsonPointerSourceMap(loadFixture("baseSpec.yaml")).build()

        val expected = mapOf(
            "" to YamlNodeLocation(1, 1, MAPPING),
            "/openapi" to YamlNodeLocation(1, 1, SCALAR),
            "/info" to YamlNodeLocation(2, 1, MAPPING),
            "/info/title" to YamlNodeLocation(3, 3, SCALAR),
            "/info/version" to YamlNodeLocation(4, 3, SCALAR),
            "/paths" to YamlNodeLocation(5, 1, MAPPING),
            "/paths/~1data" to YamlNodeLocation(6, 3, MAPPING),
            "/paths/~1data/post" to YamlNodeLocation(7, 5, MAPPING),
            "/paths/~1data/post/summary" to YamlNodeLocation(8, 7, SCALAR),
            "/paths/~1data/post/requestBody" to YamlNodeLocation(9, 7, MAPPING),
            "/paths/~1data/post/requestBody/required" to YamlNodeLocation(10, 9, SCALAR),
            "/paths/~1data/post/requestBody/content" to YamlNodeLocation(11, 9, MAPPING),
            "/paths/~1data/post/requestBody/content/application~1json" to YamlNodeLocation(12, 11, MAPPING),
            "/paths/~1data/post/requestBody/content/application~1json/schema" to YamlNodeLocation(13, 13, MAPPING),
            "/paths/~1data/post/requestBody/content/application~1json/schema/type" to YamlNodeLocation(14, 15, SCALAR),
            "/paths/~1data/post/requestBody/content/application~1json/schema/required" to YamlNodeLocation(15, 15, SEQUENCE),
            "/paths/~1data/post/requestBody/content/application~1json/schema/required/0" to YamlNodeLocation(16, 19, SCALAR),
            "/paths/~1data/post/requestBody/content/application~1json/schema/properties" to YamlNodeLocation(17, 15, MAPPING),
            "/paths/~1data/post/requestBody/content/application~1json/schema/properties/id" to YamlNodeLocation(18, 17, MAPPING),
            "/paths/~1data/post/requestBody/content/application~1json/schema/properties/id/type" to YamlNodeLocation(19, 19, SCALAR),
            "/paths/~1data/post/requestBody/content/application~1json/schema/properties/note" to YamlNodeLocation(20, 17, MAPPING),
            "/paths/~1data/post/requestBody/content/application~1json/schema/properties/note/type" to YamlNodeLocation(21, 19, SCALAR),
            "/paths/~1data/post/responses" to YamlNodeLocation(22, 7, MAPPING),
            "/paths/~1data/post/responses/200" to YamlNodeLocation(23, 9, MAPPING),
            "/paths/~1data/post/responses/200/description" to YamlNodeLocation(24, 11, SCALAR),
            "/paths/~1data/post/responses/200/content" to YamlNodeLocation(25, 11, MAPPING),
            "/paths/~1data/post/responses/200/content/application~1json" to YamlNodeLocation(26, 13, MAPPING),
            "/paths/~1data/post/responses/200/content/application~1json/schema" to YamlNodeLocation(27, 15, MAPPING),
            "/paths/~1data/post/responses/200/content/application~1json/schema/type" to YamlNodeLocation(28, 17, SCALAR),
            "/paths/~1data/post/responses/200/content/application~1json/schema/required" to YamlNodeLocation(29, 17, SEQUENCE),
            "/paths/~1data/post/responses/200/content/application~1json/schema/required/0" to YamlNodeLocation(30, 21, SCALAR),
            "/paths/~1data/post/responses/200/content/application~1json/schema/properties" to YamlNodeLocation(31, 17, MAPPING),
            "/paths/~1data/post/responses/200/content/application~1json/schema/properties/id" to YamlNodeLocation(32, 19, MAPPING),
            "/paths/~1data/post/responses/200/content/application~1json/schema/properties/id/type" to YamlNodeLocation(33, 21, SCALAR),
            "/paths/~1data/post/responses/200/content/application~1json/schema/properties/note" to YamlNodeLocation(34, 19, MAPPING),
            "/paths/~1data/post/responses/200/content/application~1json/schema/properties/note/type" to YamlNodeLocation(35, 21, SCALAR),
        )
        assertThat(map).isEqualTo(expected)
    }

    @Test
    fun `composition spec records the refTarget on the use-site and indexes the definition-site`() {
        val map = JsonPointerSourceMap(loadFixture("baseSpecWithComposition.yaml")).build()

        val expected = mapOf(
            "" to YamlNodeLocation(1, 1, MAPPING),
            "/openapi" to YamlNodeLocation(1, 1, SCALAR),
            "/info" to YamlNodeLocation(2, 1, MAPPING),
            "/info/title" to YamlNodeLocation(3, 3, SCALAR),
            "/info/version" to YamlNodeLocation(4, 3, SCALAR),
            "/paths" to YamlNodeLocation(5, 1, MAPPING),
            "/paths/~1submissions" to YamlNodeLocation(6, 3, MAPPING),
            "/paths/~1submissions/post" to YamlNodeLocation(7, 5, MAPPING),
            "/paths/~1submissions/post/summary" to YamlNodeLocation(8, 7, SCALAR),
            "/paths/~1submissions/post/requestBody" to YamlNodeLocation(9, 7, MAPPING),
            "/paths/~1submissions/post/requestBody/required" to YamlNodeLocation(10, 9, SCALAR),
            "/paths/~1submissions/post/requestBody/content" to YamlNodeLocation(11, 9, MAPPING),
            "/paths/~1submissions/post/requestBody/content/application~1json" to YamlNodeLocation(12, 11, MAPPING),
            "/paths/~1submissions/post/requestBody/content/application~1json/schema" to YamlNodeLocation(13, 13, MAPPING, refTarget = "/components/schemas/Submission"),
            $$"/paths/~1submissions/post/requestBody/content/application~1json/schema/$ref" to YamlNodeLocation(14, 15, SCALAR),
            "/paths/~1submissions/post/responses" to YamlNodeLocation(15, 7, MAPPING),
            "/paths/~1submissions/post/responses/200" to YamlNodeLocation(16, 9, MAPPING),
            "/paths/~1submissions/post/responses/200/description" to YamlNodeLocation(17, 11, SCALAR),
            "/paths/~1submissions/post/responses/200/content" to YamlNodeLocation(18, 11, MAPPING),
            "/paths/~1submissions/post/responses/200/content/application~1json" to YamlNodeLocation(19, 13, MAPPING),
            "/paths/~1submissions/post/responses/200/content/application~1json/schema" to YamlNodeLocation(20, 15, MAPPING),
            "/paths/~1submissions/post/responses/200/content/application~1json/schema/type" to YamlNodeLocation(21, 17, SCALAR),
            "/paths/~1submissions/post/responses/200/content/application~1json/schema/required" to YamlNodeLocation(22, 17, SEQUENCE),
            "/paths/~1submissions/post/responses/200/content/application~1json/schema/required/0" to YamlNodeLocation(23, 21, SCALAR),
            "/paths/~1submissions/post/responses/200/content/application~1json/schema/properties" to YamlNodeLocation(24, 17, MAPPING),
            "/paths/~1submissions/post/responses/200/content/application~1json/schema/properties/status" to YamlNodeLocation(25, 19, MAPPING),
            "/paths/~1submissions/post/responses/200/content/application~1json/schema/properties/status/type" to YamlNodeLocation(26, 21, SCALAR),
            "/paths/~1submissions/post/responses/200/content/application~1json/schema/properties/status/enum" to YamlNodeLocation(27, 21, SEQUENCE),
            "/paths/~1submissions/post/responses/200/content/application~1json/schema/properties/status/enum/0" to YamlNodeLocation(28, 25, SCALAR),
            "/components" to YamlNodeLocation(29, 1, MAPPING),
            "/components/schemas" to YamlNodeLocation(30, 3, MAPPING),
            "/components/schemas/Submission" to YamlNodeLocation(31, 5, MAPPING),
            "/components/schemas/Submission/type" to YamlNodeLocation(32, 7, SCALAR),
            "/components/schemas/Submission/required" to YamlNodeLocation(33, 7, SEQUENCE),
            "/components/schemas/Submission/required/0" to YamlNodeLocation(34, 11, SCALAR),
            "/components/schemas/Submission/required/1" to YamlNodeLocation(35, 11, SCALAR),
            "/components/schemas/Submission/required/2" to YamlNodeLocation(36, 11, SCALAR),
            "/components/schemas/Submission/properties" to YamlNodeLocation(37, 7, MAPPING),
            "/components/schemas/Submission/properties/id" to YamlNodeLocation(38, 9, MAPPING),
            "/components/schemas/Submission/properties/id/type" to YamlNodeLocation(39, 11, SCALAR),
            "/components/schemas/Submission/properties/kind" to YamlNodeLocation(40, 9, MAPPING),
            "/components/schemas/Submission/properties/kind/oneOf" to YamlNodeLocation(41, 11, SEQUENCE),
            "/components/schemas/Submission/properties/kind/oneOf/0" to YamlNodeLocation(42, 15, MAPPING),
            "/components/schemas/Submission/properties/kind/oneOf/0/type" to YamlNodeLocation(42, 15, SCALAR),
            "/components/schemas/Submission/properties/kind/oneOf/1" to YamlNodeLocation(43, 15, MAPPING),
            "/components/schemas/Submission/properties/kind/oneOf/1/type" to YamlNodeLocation(43, 15, SCALAR),
            "/components/schemas/Submission/properties/category" to YamlNodeLocation(44, 9, MAPPING),
            "/components/schemas/Submission/properties/category/type" to YamlNodeLocation(45, 11, SCALAR),
            "/components/schemas/Submission/properties/category/enum" to YamlNodeLocation(46, 11, SEQUENCE),
            "/components/schemas/Submission/properties/category/enum/0" to YamlNodeLocation(47, 15, SCALAR),
            "/components/schemas/Submission/properties/category/enum/1" to YamlNodeLocation(48, 15, SCALAR),
            "/components/schemas/Submission/properties/items" to YamlNodeLocation(49, 9, MAPPING),
            "/components/schemas/Submission/properties/items/type" to YamlNodeLocation(50, 11, SCALAR),
            "/components/schemas/Submission/properties/items/items" to YamlNodeLocation(51, 11, MAPPING),
            "/components/schemas/Submission/properties/items/items/type" to YamlNodeLocation(52, 13, SCALAR),
            "/components/schemas/Submission/properties/items/items/properties" to YamlNodeLocation(53, 13, MAPPING),
            "/components/schemas/Submission/properties/items/items/properties/name" to YamlNodeLocation(54, 15, MAPPING),
            "/components/schemas/Submission/properties/items/items/properties/name/type" to YamlNodeLocation(55, 17, SCALAR),
        )
        assertThat(map).isEqualTo(expected)
    }

    private fun loadFixture(name: String): String =
        this::class.java.classLoader.getResource("jsonPointerSourceMap/$name")!!.readText()
}
