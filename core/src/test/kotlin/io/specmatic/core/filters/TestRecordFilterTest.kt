import io.specmatic.core.filters.TestRecordFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TestRecordFilterTest {
    private fun testResultRecord(
        path: String = "/api/test",
        method: String = "GET",
        responseStatus: Int = 200
    ) = io.specmatic.test.TestResultRecord(
        path = path,
        method = method,
        responseStatus = responseStatus,
        result = io.specmatic.core.TestResult.Success
    )

    @Test
    fun `includes returns true when path contains any value`() {
        val filter = TestRecordFilter(testResultRecord(path = "/api/foo/bar"))
        assertThat(filter.includes("PATH", listOf("foo"))).isTrue
        assertThat(filter.includes("PATH", listOf("baz"))).isFalse
    }

    @Test
    fun `includes returns true when method matches ignoring case`() {
        val filter = TestRecordFilter(testResultRecord(method = "POST"))
        assertThat(filter.includes("METHOD", listOf("post"))).isTrue
        assertThat(filter.includes("METHOD", listOf("get"))).isFalse
    }

    @Test
    fun `includes returns true when status matches`() {
        val filter = TestRecordFilter(testResultRecord(responseStatus = 201))
        assertThat(filter.includes("STATUS", listOf("201"))).isTrue
        assertThat(filter.includes("STATUS", listOf("404"))).isFalse
        assertThat(filter.includes("STATUS", listOf("notANumber"))).isFalse
    }

    @Test
    fun `includes returns true for unknown key`() {
        val filter = TestRecordFilter(testResultRecord())
        assertThat(filter.includes("UNKNOWN", listOf("anything"))).isTrue
    }

    @Test
    fun `compare returns true for STATUS with matching operator`() {
        val filter = TestRecordFilter(testResultRecord(responseStatus = 200))
        assertThat(filter.compare("STATUS", ">", "100")).isTrue
        assertThat(filter.compare("STATUS", "<", "300")).isTrue
    }

    @Test
    fun `compare returns true for unknown key`() {
        val filter = TestRecordFilter(testResultRecord())
        assertThat(filter.compare("FOO", "=", "bar")).isTrue
    }
}
