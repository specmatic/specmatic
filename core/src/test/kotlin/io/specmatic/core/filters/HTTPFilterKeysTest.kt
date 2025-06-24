import io.specmatic.core.filters.HTTPFilterKeys
import io.specmatic.core.filters.caseInsensitiveContains
import io.specmatic.core.filters.caseSensitiveContains
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HTTPFilterKeysTest {

    @Test
    fun `fromKey returns correct enum for exact match`() {
        val result = HTTPFilterKeys.fromKey("PATH")
        assertThat(result).isEqualTo(HTTPFilterKeys.PATH)
    }

    @Test
    fun `fromKey returns correct enum for prefix match`() {
        val result = HTTPFilterKeys.fromKey("PARAMETERS.HEADER.SomeValue")
        assertThat(result).isEqualTo(HTTPFilterKeys.PARAMETERS_HEADER_WITH_SPECIFIC_VALUE)
    }

    @Test
    fun `fromKey throws exception for invalid key`() {
        assertThatThrownBy { HTTPFilterKeys.fromKey("INVALID.KEY") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid filter key: INVALID.KEY")
    }

    @Test
    fun `fromKey returns correct enum for another exact match`() {
        val result = HTTPFilterKeys.fromKey("METHOD")
        assertThat(result).isEqualTo(HTTPFilterKeys.METHOD)
    }

    @Test
    fun `fromKey returns correct enum for another prefix match`() {
        val result = HTTPFilterKeys.fromKey("PARAMETERS.QUERY.SomeValue")
        assertThat(result).isEqualTo(HTTPFilterKeys.PARAMETERS_QUERY_WITH_SPECIFIC_VALUE)
    }

    @Test
    fun `fromKey key cannot be empty`() {
        assertThatThrownBy { HTTPFilterKeys.fromKey("") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid filter key: ")
    }

    @Test
    fun `fromKey key cannot be blank`() {
        assertThatThrownBy { HTTPFilterKeys.fromKey("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid filter key:    ")
    }

    @Test
    fun `fromKey key is case sensitive`() {
        assertThatThrownBy { HTTPFilterKeys.fromKey("path") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid filter key: path")
    }

    @Test
    fun `fromKey invalid key`() {
        assertThatThrownBy { HTTPFilterKeys.fromKey("INVALID_KEY") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid filter key: INVALID_KEY")
    }

    @Test
    fun `caseInsensitiveContains returns true when exact string is present`() {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseInsensitiveContains("banana")).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "BANANA",
        "APPLE",
        "Cherry"
    )
    fun `caseInsensitiveContains returns true when string is present with different casing`(needle: String) {
        val items = listOf("apple", "BANANA", "cherry")
        assertThat(items.caseInsensitiveContains(needle)).isTrue()
    }

    @Test
    fun `caseInsensitiveContains returns false when string is not present`() {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseInsensitiveContains("orange")).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "apple",
        "cherry",
        "banana"
    )
    fun `caseInsensitiveContains handles trailing question mark in haystack items`(needle: String) {
        val items = listOf("apple?", "banana", "cherry?")
        assertThat(items.caseInsensitiveContains(needle)).isTrue()
    }

    @Test
    fun `caseInsensitiveContains works with empty lists`() {
        val emptyItems = emptyList<String>()
        assertThat(emptyItems.caseInsensitiveContains("anything")).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "apple",
        "BANANA",
        "Cherry"
    )
    fun `caseInsensitiveContains handles whitespace and trimming`(needle: String) {
        val items = listOf("  apple  ", "\tbanana\t", " cherry ")
        assertThat(items.caseInsensitiveContains(needle)).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "apple",
        "BANANA",
        "Cherry"
    )
    fun `caseInsensitiveContains handles whitespace with question marks`(needle: String) {
        val items = listOf("  apple?  ", "\tbanana?\t", " cherry? ")
        assertThat(items.caseInsensitiveContains(needle)).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "apple?",
        "banana?"
    )
    fun `caseInsensitiveContains does not match needle with question mark when haystack has no question mark`(needle: String) {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseInsensitiveContains(needle)).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "banana,true",
        "apple,true"
    )
    fun `caseSensitiveContains returns true when exact string is present`(needle: String, expected: Boolean) {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "APPLE,false",
        "banana,false",
        "Cherry,false",
        "apple,true",
        "BANANA,true",
        "cherry,true"
    )
    fun `caseSensitiveContains returns false when string is present with different casing`(needle: String, expected: Boolean) {
        val items = listOf("apple", "BANANA", "cherry")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "orange,false",
        "grape,false"
    )
    fun `caseSensitiveContains returns false when string is not present`(needle: String, expected: Boolean) {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "apple,true",
        "cherry,true",
        "banana,true"
    )
    fun `caseSensitiveContains handles trailing question mark in haystack items`(needle: String, expected: Boolean) {
        val items = listOf("apple?", "banana", "cherry?")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @Test
    fun `caseSensitiveContains works with empty lists`() {
        val emptyItems = emptyList<String>()
        assertThat(emptyItems.caseSensitiveContains("anything")).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "apple,true",
        "banana,true",
        "cherry,true",
        "APPLE,false"
    )
    fun `caseSensitiveContains handles whitespace and trimming`(needle: String, expected: Boolean) {
        val items = listOf("  apple  ", "\tbanana\t", " cherry ")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "apple,true",
        "banana,true",
        "cherry,true",
        "APPLE,false"
    )
    fun `caseSensitiveContains handles whitespace with question marks`(needle: String, expected: Boolean) {
        val items = listOf("  apple?  ", "\tbanana?\t", " cherry? ")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "apple?,false",
        "banana?,false"
    )
    fun `caseSensitiveContains does not match needle with question mark when haystack has no question mark`(needle: String, expected: Boolean) {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "apple?,true",
        "banana,true"
    )
    fun `both functions handle multiple question marks correctly`(needle: String, expected: Boolean) {
        val items = listOf("apple??", "banana?", "cherry")
        if(needle == "apple?") {
            assertThat(items.caseInsensitiveContains(needle)).isEqualTo(expected)
            assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
        } else {
            assertThat(items.caseInsensitiveContains(needle)).isEqualTo(expected)
            assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
        }
    }

    @Test
    fun `both functions handle empty strings in collections`() {
        val items = listOf("", "apple", "banana")
        assertThat(items.caseInsensitiveContains("")).isTrue()
        assertThat(items.caseSensitiveContains("")).isTrue()
    }

    @Test
    fun `both functions handle collections with only empty and whitespace strings`() {
        val items = listOf("", "   ", "\t\t")
        assertThat(items.caseInsensitiveContains("")).isTrue()
        assertThat(items.caseSensitiveContains("")).isTrue()
    }

}
