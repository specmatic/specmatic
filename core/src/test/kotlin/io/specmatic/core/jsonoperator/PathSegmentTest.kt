package io.specmatic.core.jsonoperator

import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PathSegmentTest {
    @Test
    fun `should parse simple JSON pointer path with keys`() {
        val result = PathSegment.parsePath("/name/first")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).hasSize(2)
        assertThat(segments[0]).isInstanceOf(PathSegment.Key::class.java)
        assertThat((segments[0] as PathSegment.Key).key).isEqualTo("name")
        assertThat(segments[1]).isInstanceOf(PathSegment.Key::class.java)
        assertThat((segments[1] as PathSegment.Key).key).isEqualTo("first")
    }

    @Test
    fun `should parse JSON pointer path with indices`() {
        val result = PathSegment.parsePath("/array/0/value")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).hasSize(3)
        assertThat(segments[0]).isInstanceOf(PathSegment.Key::class.java)
        assertThat((segments[0] as PathSegment.Key).key).isEqualTo("array")
        assertThat(segments[1]).isInstanceOf(PathSegment.Index::class.java)
        assertThat((segments[1] as PathSegment.Index).index).isEqualTo(0)
        assertThat(segments[2]).isInstanceOf(PathSegment.Key::class.java)
        assertThat((segments[2] as PathSegment.Key).key).isEqualTo("value")
    }

    @Test
    fun `should parse JSON pointer path with negative indices`() {
        val result = PathSegment.parsePath("/array/-1")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).hasSize(2)
        assertThat(segments[1]).isInstanceOf(PathSegment.Index::class.java)
        assertThat((segments[1] as PathSegment.Index).index).isEqualTo(-1)
    }

    @Test
    fun `should handle JSON pointer with special character normalization`() {
        val result = PathSegment.parsePath("/name#address.city")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).hasSize(3)
        assertThat((segments[0] as PathSegment.Key).key).isEqualTo("name")
        assertThat((segments[1] as PathSegment.Key).key).isEqualTo("address")
        assertThat((segments[2] as PathSegment.Key).key).isEqualTo("city")
    }

    @Test
    fun `should decode JSON pointer escape sequences`() {
        val result = PathSegment.parsePath("/key~0name/key~1path")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).hasSize(2)
        assertThat((segments[0] as PathSegment.Key).key).isEqualTo("key~name")
        assertThat((segments[1] as PathSegment.Key).key).isEqualTo("key/path")
    }

    @Test
    fun `should parse internal JSON pointer with array notation`() {
        val result = PathSegment.parsePath("items[0]")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).hasSize(2)
        assertThat(segments[0]).isInstanceOf(PathSegment.Key::class.java)
        assertThat((segments[0] as PathSegment.Key).key).isEqualTo("items")
        assertThat(segments[1]).isInstanceOf(PathSegment.Index::class.java)
        assertThat((segments[1] as PathSegment.Index).index).isEqualTo(0)
    }

    @Test
    fun `should parse internal JSON pointer with multiple array indices`() {
        val result = PathSegment.parsePath("data[1][2]")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).hasSize(3)
        assertThat((segments[0] as PathSegment.Key).key).isEqualTo("data")
        assertThat((segments[1] as PathSegment.Index).index).isEqualTo(1)
        assertThat((segments[2] as PathSegment.Index).index).isEqualTo(2)
    }

    @Test
    fun `should parse internal JSON pointer with negative array index`() {
        val result = PathSegment.parsePath("items[-1]")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).hasSize(2)
        assertThat((segments[1] as PathSegment.Index).index).isEqualTo(-1)
    }

    @Test
    fun `should return empty list for blank path`() {
        val result = PathSegment.parsePath("")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).isEmpty()
    }

    @Test
    fun `should return empty list for whitespace path`() {
        val result = PathSegment.parsePath("   ")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).isEmpty()
    }

    @Test
    fun `should preserve parsedPath in Key segment`() {
        val path = "/name/first"
        val result = PathSegment.parsePath(path)
        val segments = (result as HasValue).value
        assertThat((segments[0] as PathSegment.Key).parsedPath).isEqualTo(path)
        assertThat((segments[1] as PathSegment.Key).parsedPath).isEqualTo(path)
    }

    @Test
    fun `should preserve parsedPath in Index segment`() {
        val path = "/array/0"
        val result = PathSegment.parsePath(path)
        val segments = (result as HasValue).value
        assertThat((segments[1] as PathSegment.Index).parsedPath).isEqualTo(path)
    }

    @Test
    fun `Key toString should return key value`() {
        val key = PathSegment.Key("testKey", "/testKey")
        assertThat(key.toString()).isEqualTo("testKey")
    }

    @Test
    fun `Index toString should return index as string`() {
        val index = PathSegment.Index(42, "/42")
        assertThat(index.toString()).isEqualTo("42")
    }

    @Test
    fun `takeNextAs should return HasValue when type matches`() {
        val segments = listOf<PathSegment>(PathSegment.Key("test", "/test"))
        val result = segments.takeNextAs<PathSegment.Key>()
        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value.key).isEqualTo("test")
    }

    @Test
    fun `takeNextAs should return HasFailure when type does not match`() {
        val segments = listOf<PathSegment>(PathSegment.Key("test", "/test"))
        val result = segments.takeNextAs<PathSegment.Index>()
        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString()).contains("Unexpected path segment")
        assertThat(result.failure.reportString()).contains("expected: Index")
        assertThat(result.failure.reportString()).contains("got Key")
    }

    @Test
    fun `takeNextAs should throw IllegalStateException when collection is empty`() {
        val segments = emptyList<PathSegment>()
        assertThrows<IllegalStateException> {
            segments.takeNextAs<PathSegment.Key>()
        }
    }

    @Test
    fun `should parse complex mixed path`() {
        val result = PathSegment.parsePath("/users/0/addresses/1/city")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).hasSize(5)
        assertThat((segments[0] as PathSegment.Key).key).isEqualTo("users")
        assertThat((segments[1] as PathSegment.Index).index).isEqualTo(0)
        assertThat((segments[2] as PathSegment.Key).key).isEqualTo("addresses")
        assertThat((segments[3] as PathSegment.Index).index).isEqualTo(1)
        assertThat((segments[4] as PathSegment.Key).key).isEqualTo("city")
    }

    @Test
    fun `should parse path starting with leading slash`() {
        val result = PathSegment.parsePath("/root")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).hasSize(1)
        assertThat((segments[0] as PathSegment.Key).key).isEqualTo("root")
    }

    @Test
    fun `should handle purely numeric string as index when using JSON pointer format`() {
        val result = PathSegment.parsePath("/123")
        assertThat(result).isInstanceOf(HasValue::class.java)
        val segments = (result as HasValue).value
        assertThat(segments).hasSize(1)
        assertThat(segments[0]).isInstanceOf(PathSegment.Index::class.java)
        assertThat((segments[0] as PathSegment.Index).index).isEqualTo(123)
    }
}
