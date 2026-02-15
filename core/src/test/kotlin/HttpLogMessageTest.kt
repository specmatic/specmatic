import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.log.CurrentDate
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class HttpLogMessageTest {
    private val dateTime: CurrentDate = CurrentDate()
    private val httpLog = HttpLogMessage(
        requestTime =dateTime,
        request = HttpRequest("GET", "/"),
        responseTime = dateTime,
        response = HttpResponse.OK,
        contractPath = "/path/to/file"
    )

    @Test
    fun `render an http log message as JSON`() {
        val json: JSONObjectValue = httpLog.toJSONObject()
        assertThat(json.getString("requestTime")).isEqualTo(dateTime.toString())
        assertThat(json.getString("responseTime")).isEqualTo(dateTime.toString())
        assertThat(json.getString("contractMatched")).isEqualTo("/path/to/file")

        assertThat(json.findFirstChildByPath("http-request.path")).isEqualTo(StringValue("/"))
        assertThat(json.findFirstChildByPath("http-request.method")).isEqualTo(StringValue("GET"))
        assertThat(json.findFirstChildByPath("http-request.body")).isEqualTo(StringValue(""))

        assertThat(json.findFirstChildByPath("http-response.status")).isEqualTo(NumberValue(200))
        assertThat(json.findFirstChildByPath("http-response.status-text")).isEqualTo(StringValue("OK"))
        assertThat(json.findFirstChildByPath("http-response.body")).isEqualTo(StringValue(""))
    }

    @Test
    fun `render an http log message as Text`() {
        val text: String = httpLog.toLogString()

        println(text)

        assertThat(text).contains("$dateTime")
        assertThat(text).contains("GET /")

        assertThat(text).contains("200 OK")
        assertThat(text).contains("$dateTime")

        assertThat(text).contains("/path/to/file")
    }
}