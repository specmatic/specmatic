import io.specmatic.core.filters.HTTPFilterKeys
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

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
}
