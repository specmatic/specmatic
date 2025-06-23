package io.specmatic.core

import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.assertj.core.api.Assertions.assertThat

class ScenarioSOAPActionTest {
    @Test
    fun `apiDescription should include SOAPAction when present`() {
        // Create headers pattern with SOAPAction
        val soapActionValue = "http://example.com/soap/action"
        val headersPattern = HttpHeadersPattern(
            pattern = mapOf(
                "SOAPAction" to ExactValuePattern(StringValue(soapActionValue)),
                "Content-Type" to ExactValuePattern(StringValue("application/xml"))
            )
        )
        
        val httpRequestPattern = HttpRequestPattern(
            headersPattern = headersPattern,
            method = "POST",
            httpPathPattern = HttpPathPattern(listOf(), "/soap/service")
        )
        
        val httpResponsePattern = HttpResponsePattern(status = 200)
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = httpRequestPattern,
            httpResponsePattern = httpResponsePattern
        )
        
        // Test that the API description includes SOAPAction
        val description = scenario.apiDescription
        assertThat(description).isEqualTo("POST /soap/service SOAPAction http://example.com/soap/action -> 200")
    }
    
    @Test
    fun `apiDescription should work normally when SOAPAction is not present`() {
        // Create headers pattern without SOAPAction
        val headersPattern = HttpHeadersPattern(
            pattern = mapOf(
                "Content-Type" to ExactValuePattern(StringValue("application/json"))
            )
        )
        
        val httpRequestPattern = HttpRequestPattern(
            headersPattern = headersPattern,
            method = "GET",
            httpPathPattern = HttpPathPattern(listOf(), "/api/data")
        )
        
        val httpResponsePattern = HttpResponsePattern(status = 200)
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = httpRequestPattern,
            httpResponsePattern = httpResponsePattern
        )
        
        // Test that the API description works normally
        val description = scenario.apiDescription
        assertThat(description).isEqualTo("GET /api/data -> 200")
    }
    
    @ParameterizedTest
    @CsvSource("SOAPAction", "soapaction", "SoapAction", "SOAPACTION")
    fun `apiDescription should handle case insensitive SOAPAction header`(headerName: String) {
        val soapActionValue = "http://example.com/soap/action"
        val headersPattern = HttpHeadersPattern(
            pattern = mapOf(
                headerName to ExactValuePattern(StringValue(soapActionValue))
            )
        )
        
        val httpRequestPattern = HttpRequestPattern(
            headersPattern = headersPattern,
            method = "POST",
            httpPathPattern = HttpPathPattern(listOf(), "/soap/service")
        )
        
        val httpResponsePattern = HttpResponsePattern(status = 200)
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = httpRequestPattern,
            httpResponsePattern = httpResponsePattern
        )
        
        // Test that the API description includes SOAPAction regardless of case
        val description = scenario.apiDescription
        assertThat(description).isEqualTo("POST /soap/service SOAPAction http://example.com/soap/action -> 200")
    }
    
    @Test
    fun `apiDescription should ignore non-ExactValuePattern SOAPAction headers`() {
        // Create headers pattern with SOAPAction as a StringPattern (not exact value)
        val headersPattern = HttpHeadersPattern(
            pattern = mapOf(
                "SOAPAction" to StringPattern()
            )
        )
        
        val httpRequestPattern = HttpRequestPattern(
            headersPattern = headersPattern,
            method = "POST",
            httpPathPattern = HttpPathPattern(listOf(), "/soap/service")
        )
        
        val httpResponsePattern = HttpResponsePattern(status = 200)
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = httpRequestPattern,
            httpResponsePattern = httpResponsePattern
        )
        
        // Test that the API description does not include SOAPAction for non-exact patterns
        val description = scenario.apiDescription
        assertThat(description).isEqualTo("POST /soap/service -> 200")
    }
    
    @Test
    fun `testDescription should include SOAPAction in the scenario description`() {
        // Test that the testDescription method properly includes the SOAPAction info
        val soapActionValue = "http://example.com/soap/action"
        val headersPattern = HttpHeadersPattern(
            pattern = mapOf(
                "SOAPAction" to ExactValuePattern(StringValue(soapActionValue))
            )
        )
        
        val httpRequestPattern = HttpRequestPattern(
            headersPattern = headersPattern,
            method = "POST",
            httpPathPattern = HttpPathPattern(listOf(), "/soap/service")
        )
        
        val httpResponsePattern = HttpResponsePattern(status = 200)
        
        val scenario = Scenario(
            name = "SOAP Service Test",
            httpRequestPattern = httpRequestPattern,
            httpResponsePattern = httpResponsePattern
        )
        
        // Test that the test description includes SOAPAction
        val testDescription = scenario.testDescription()
        assertThat(testDescription).isEqualTo(" Scenario: POST /soap/service SOAPAction http://example.com/soap/action -> 200")
    }
}