package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.value.toXMLNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class SOAPCDATAIntegrationTest {
    @Test
    fun `SOAP 1_1 stub loads WSDL with CDATA in request and escaped XML in response`() {
        createStub(
            timeoutMillis = 0,
            givenConfigFileName = "src/test/resources/wsdl/cdata_test/specmatic.yaml",
        ).use { stub ->
            val request =
                HttpRequest(
                    method = "POST",
                    path = "/",
                    headers =
                        mapOf(
                            "Content-Type" to "text/xml",
                            "SOAPAction" to "\"/messages/process\"",
                        ),
                    body =
                        toXMLNode(
                            """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body><ns0:ProcessMessage xmlns:ns0="http://www.example.com/messages"><content><![CDATA[<data>test & special chars</data>]]></content></ns0:ProcessMessage></soapenv:Body></soapenv:Envelope>""",
                        ),
                )

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            assertThat(response.headers["Content-Type"]).isEqualTo("text/xml")

            val responseBody = response.body.toStringLiteral()
            assertThat(responseBody).contains("&lt;response&gt;success &amp; processed&lt;/response&gt;")
        }
    }

    @Test
    fun `SOAP 1_2 stub loads WSDL with CDATA and XML escaping via action in Content-Type`() {
        createStub(
            timeoutMillis = 0,
            givenConfigFileName = "src/test/resources/wsdl/cdata_test_soap12/specmatic.yaml",
        ).use { stub ->
            val request =
                HttpRequest(
                    method = "POST",
                    path = "/",
                    headers =
                        mapOf(
                            "Content-Type" to "application/soap+xml; action=\"http://www.example.com/data/store\"",
                        ),
                    body =
                        toXMLNode(
                            """<env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"><env:Body><ns0:StoreData xmlns:ns0="http://www.example.com/data"><xmlData><![CDATA[<xml>nested & special</xml>]]></xmlData></ns0:StoreData></env:Body></env:Envelope>""",
                        ),
                )

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            assertThat(response.headers["Content-Type"]).isEqualTo("application/soap+xml")

            val responseBody = response.body.toStringLiteral()
            assertThat(responseBody).contains("&lt;result&gt;OK&lt;/result&gt;")
        }
    }
}
