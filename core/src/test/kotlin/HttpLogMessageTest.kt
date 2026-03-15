import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.config.LogRedactionConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.log.LogRedactionContext
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.log.CurrentDate
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import java.io.File

internal class HttpLogMessageTest {
    private val feature = parseContractFileToFeature(File("src/test/resources/openapi/partial_example_tests/simple.yaml"))
    private val scenario = feature.scenarios.first()
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
        assertThat(json.findFirstChildByPath("requestId")).isNotNull()
    }

    @Test
    fun `rendered http log json should apply redaction policy`() {
        LogRedactionContext.start(
            LoggingConfiguration(
                redaction = LogRedactionConfig(headers = setOf("X-Private"), jsonKeys = setOf("token"), mask = "<hidden>"),
            ),
        )
        try {
            val message =
                HttpLogMessage(
                    requestTime = dateTime,
                    request =
                        HttpRequest(
                            "POST",
                            "/",
                            headers = mapOf("X-Private" to "secret"),
                            body = io.specmatic.core.pattern.parsedValue("""{"token":"abc"}"""),
                        ),
                    responseTime = dateTime,
                    response =
                        HttpResponse(
                            200,
                            headers = mapOf("Set-Cookie" to "session=123"),
                            body = io.specmatic.core.pattern.parsedValue("""{"token":"xyz"}"""),
                        ),
                )

            val json = message.toJSONObject()
            assertThat(json.findFirstChildByPath("http-request.headers.X-Private")).isEqualTo(StringValue("<hidden>"))
            assertThat(json.findFirstChildByPath("http-request.body.token")).isEqualTo(StringValue("<hidden>"))
            assertThat(json.findFirstChildByPath("http-response.headers.Set-Cookie")).isEqualTo(StringValue("<hidden>"))
            assertThat(json.findFirstChildByPath("http-response.body.token")).isEqualTo(StringValue("<hidden>"))
        } finally {
            LogRedactionContext.start(LoggingConfiguration.default())
        }
    }

    @Test
    fun `render an http log message as Text`() {
        val text: String = httpLog.toLogString()

        println(text)

        assertThat(text).contains("$dateTime")
        assertThat(text).contains("GET /")
        assertThat(text).contains("correlationId=")
        assertThat(text).contains("requestId=")

        assertThat(text).contains("200 OK")
        assertThat(text).contains("$dateTime")

        assertThat(text).contains("/path/to/file")
    }

    @Test
    fun `http log message includes structured error details when exception is present`() {
        val message = HttpLogMessage(request = HttpRequest("GET", "/")).apply {
            addException(IllegalArgumentException("bad input"))
        }

        val json = message.toJSONObject()
        assertThat(json.findFirstChildByPath("error.errorCode")).isEqualTo(StringValue("IllegalArgumentException"))
        assertThat(json.findFirstChildByPath("error.errorCategory")).isEqualTo(StringValue("VALIDATION"))
        assertThat(json.findFirstChildByPath("error.message")).isEqualTo(StringValue("bad input"))
    }

    @ParameterizedTest
    @CsvSource(
        useHeadersInDisplayName = true,
        value = [
            "method,path,expected",
            "GET,/_specmatic/log,true",
            "GET,/swagger/v1/swagger.yaml,true",
            "HEAD,/,true",
            "GET,/actuator/health,true",
            "GET,/orders,false",
        ]
    )
    fun `should identify whether request is an internal control request`(
        method: String,
        path: String,
        expected: Boolean
    ) {
        val message = HttpLogMessage(request = HttpRequest(method, path))
        assertThat(message.isInternalControlRequestForMockEvent()).isEqualTo(expected)
    }

    @Test
    fun `toName should mention inline example for internal example matches`() {
        val message = HttpLogMessage(
            request = scenario.generateHttpRequest(),
            response = scenario.generateHttpResponse(emptyMap()),
            contractPath = "/path/to/file",
            scenario = scenario,
            exampleName = "FIND_SUCCESS"
        )
        assertThat(message.toName()).contains("inline example 'FIND_SUCCESS'")
    }

    @Test
    fun `toName should mention external example for external examples matches`() {
        val message = HttpLogMessage(
            request = scenario.generateHttpRequest(),
            response = scenario.generateHttpResponse(emptyMap()),
            contractPath = "/path/to/file",
            scenario = scenario,
            examplePath = "examples/example.json"
        )
        assertThat(message.toName()).contains("external example 'example.json'")
    }

    @Test
    fun `toDetails should mention inline example name for inline example matches`() {
        val message = HttpLogMessage(
            request = scenario.generateHttpRequest(),
            response = scenario.generateHttpResponse(emptyMap()),
            contractPath = "/path/to/file",
            scenario = scenario,
            exampleName = "FIND_SUCCESS"
        )

        assertThat(message.toDetails()).isEqualTo("Request Matched Inline Example: FIND_SUCCESS")
    }

    @Test
    fun `toDetails should mention external example path for external example matches`() {
        val message = HttpLogMessage(
            request = scenario.generateHttpRequest(),
            response = scenario.generateHttpResponse(emptyMap()),
            contractPath = "/path/to/file",
            scenario = scenario,
            examplePath = "examples/example.json"
        )

        assertThat(message.toDetails()).isEqualTo("Request Matched External Example: examples/example.json")
    }
}
