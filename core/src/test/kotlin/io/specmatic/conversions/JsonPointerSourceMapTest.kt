package io.specmatic.conversions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JsonPointerSourceMapTest {
    @Test
    fun `empty yaml produces empty map`() {
        val map = JsonPointerSourceMap("").build()
        assertThat(map).isEmpty()
    }

    @Test
    fun `root scalar is at the empty pointer`() {
        val map = JsonPointerSourceMap("hello").build()
        assertThat(map).containsEntry("", YamlNodeLocation(line = 1, column = 1, nodeKind = "scalar"))
    }

    @Test
    fun `top-level mapping entries are pointed to their value's location`() {
        val yaml = """
            openapi: 3.0.0
            info:
              title: Pets
        """.trimIndent()

        val map = JsonPointerSourceMap(yaml).build()

        assertThat(map[""]).isEqualTo(YamlNodeLocation(1, 1, "mapping"))
        assertThat(map["/openapi"]).isEqualTo(YamlNodeLocation(1, 10, "scalar"))
        assertThat(map["/info"]).isEqualTo(YamlNodeLocation(3, 3, "mapping"))
        assertThat(map["/info/title"]).isEqualTo(YamlNodeLocation(3, 10, "scalar"))
    }

    @Test
    fun `sequence elements are indexed numerically`() {
        val yaml = """
            servers:
              - url: https://a
              - url: https://b
        """.trimIndent()

        val map = JsonPointerSourceMap(yaml).build()

        assertThat(map["/servers"]?.nodeKind).isEqualTo("sequence")
        assertThat(map["/servers/0"]?.nodeKind).isEqualTo("mapping")
        assertThat(map["/servers/0/url"]).isEqualTo(YamlNodeLocation(2, 10, "scalar"))
        assertThat(map["/servers/1/url"]).isEqualTo(YamlNodeLocation(3, 10, "scalar"))
    }

    @Test
    fun `path keys containing slashes are escaped per RFC 6901`() {
        val yaml = """
            paths:
              /pets:
                get:
                  summary: list
        """.trimIndent()

        val map = JsonPointerSourceMap(yaml).build()

        assertThat(map["/paths/~1pets"]?.nodeKind).isEqualTo("mapping")
        assertThat(map["/paths/~1pets/get/summary"]).isEqualTo(YamlNodeLocation(4, 16, "scalar"))
    }

    @Test
    fun `tilde in keys is escaped as ~0`() {
        val yaml = """
            a~b: 1
        """.trimIndent()

        val map = JsonPointerSourceMap(yaml).build()

        assertThat(map).containsKey("/a~0b")
    }
}
