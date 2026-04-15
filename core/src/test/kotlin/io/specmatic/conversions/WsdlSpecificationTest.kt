package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

internal class WsdlSpecificationTest {
    @Test
    fun `support for nillable element`() {
        val wsdlPath = "src/test/resources/wsdl/stockquote.wsdl"

        val wsdlContract = wsdlContentToFeature(File(wsdlPath).readText(), wsdlPath)

        val httpRequest = HttpRequest(
            method = "POST",
            path = "/stockquote",
            headers = mapOf("SOAPAction" to "\"http://example.com/GetLastTradePrice\""),
            body = StringValue("""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><TradePriceRequest><tickerSymbol nil="true"/></TradePriceRequest></soapenv:Body></soapenv:Envelope>""")
        )

        val scenario = wsdlContract.scenarios.first()
        val result = scenario.httpRequestPattern.matches(httpRequest, scenario.resolver)

        println(result.toReport().toText())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `request and response content type should be parsed as text-xml for soap version_1_1`() {
        val wsdlPath = "src/test/resources/wsdl/stockquote.wsdl"
        val wsdlContract = wsdlContentToFeature(File(wsdlPath).readText(), wsdlPath)
        assertThat(wsdlContract.scenarios).allSatisfy { scenario ->
            val request = scenario.generateHttpRequest()
            assertThat(request.contentType()).isEqualTo("text/xml")

            val response = scenario.generateHttpResponse(emptyMap())
            assertThat(response.contentType()).isEqualTo("text/xml")
        }
    }

    @Test
    fun `request and response content type should be parsed as application-soap-xml for soap version_1_2`() {
        val wsdlPath = "src/test/resources/wsdl/cdata_test_soap12/data_api.wsdl"
        val wsdlContract = wsdlContentToFeature(File(wsdlPath).readText(), wsdlPath)
        assertThat(wsdlContract.scenarios).allSatisfy { scenario ->
            val request = scenario.generateHttpRequest()
            assertThat(request.contentType()).isEqualTo("application/soap+xml")

            val response = scenario.generateHttpResponse(emptyMap())
            assertThat(response.contentType()).isEqualTo("application/soap+xml")
        }
    }

    @Test
    fun `request and response content type should default to application-soap-xml for unknown soap version`() {
        val wsdlPath = "src/test/resources/wsdl/stockquote.wsdl"
        val wsdlContentWithUnknownSoapNamespace = File(wsdlPath).readText()
            .replace("http://schemas.xmlsoap.org/wsdl/soap/", "http://example.com/unknown-soap/")

        val wsdlContract = wsdlContentToFeature(wsdlContentWithUnknownSoapNamespace, wsdlPath)
        assertThat(wsdlContract.scenarios).allSatisfy { scenario ->
            val request = scenario.generateHttpRequest()
            assertThat(request.contentType()).isEqualTo("application/soap+xml")

            val response = scenario.generateHttpResponse(emptyMap())
            assertThat(response.contentType()).isEqualTo("application/soap+xml")
        }
    }
}
