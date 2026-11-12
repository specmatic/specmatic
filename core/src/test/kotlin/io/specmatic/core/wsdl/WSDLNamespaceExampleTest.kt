package io.specmatic.core.wsdl

import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.utilities.parseXML
import org.junit.jupiter.api.Test
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLNamespaceExampleTest {
    @TempDir lateinit var directory: File

    @ParameterizedTest(name = "{0}")
    @MethodSource("qualifiedRequests")
    fun `mock examples match namespace identities and exact values`(description: String, body: String, status: Int, matchesExample: Boolean) {
        assertMockResponse(description, body, status, matchesExample)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unqualifiedRequests")
    fun `mock examples preserve unqualified children and attributes`(description: String, body: String, status: Int, matchesExample: Boolean) {
        assertMockResponse(description, body, status, matchesExample,
            defaults = "elementFormDefault=\"unqualified\" attributeFormDefault=\"unqualified\"",
            exampleBody = """<Envelope xmlns="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="urn:orders">
                <Body><o:Request id="42"><code xmlns="">AB1</code></o:Request></Body></Envelope>""")
    }

    @Test
    fun `mock examples supplied as parsed XML retain namespace requirements`() {
        assertMockResponse("parsed example", envelope("""<urn:Request urn:id="42"><urn:code>AB1</urn:code></urn:Request>"""), 200, true, parsedExample = true)
    }

    @Test
    fun `business Envelope and Header elements retain their sequence order`() {
        val fixture = WSDLNamespaceFixture("urn:orders", "/namespaces")
        val declarations = """<xs:element name="Request"><xs:complexType><xs:sequence>
            <xs:element name="Envelope"><xs:complexType><xs:sequence>
            <xs:element name="Header"><xs:complexType><xs:sequence>
            <xs:element name="first" type="xs:string"/><xs:element name="second" type="xs:string"/>
            </xs:sequence></xs:complexType></xs:element></xs:sequence></xs:complexType></xs:element>
            </xs:sequence></xs:complexType></xs:element><xs:element name="Response" type="xs:string"/>"""
        val feature = parseContractFileToFeature(directory.resolve("business-header.wsdl").apply {
            writeText(fixture.wsdl(fixture.schema(declarations, "elementFormDefault=\"qualified\"")))
        })
        val scenario = feature.scenarios.single()
        val ordered = "<urn:first>one</urn:first><urn:second>two</urn:second>"
        val body = envelope("<urn:Request><urn:Envelope><urn:Header>$ordered</urn:Header></urn:Envelope></urn:Request>")
        val example = scenario.generateHttpRequest().copy(body = StringValue(body))
        val response = scenario.generateHttpResponse().copy(headers = emptyMap(), body = StringValue(
            envelope("<urn:Response>EXAMPLE_OK</urn:Response>")
        ))
        HttpStub(feature, scenarioStubs = listOf(ScenarioStub(request = example, response = response)), port = testPort()).use { stub ->
            assertThat(stub.client.execute(example).body.toStringLiteral()).contains("EXAMPLE_OK")
            val extraHeader = body.replace("</urn:Header>", "<urn:Header/></urn:Header>")
            assertThat(stub.client.execute(example.copy(body = StringValue(extraHeader))).status).isEqualTo(400)
            val reordered = body.replace(ordered, "<urn:second>two</urn:second><urn:first>one</urn:first>")
            assertThat(stub.client.execute(example.copy(body = StringValue(reordered))).status).isEqualTo(400)
        }
    }

    private fun assertMockResponse(
        description: String,
        body: String,
        status: Int,
        matchesExample: Boolean,
        defaults: String = "elementFormDefault=\"qualified\" attributeFormDefault=\"qualified\"",
        exampleBody: String = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
            <s:Body><Request xmlns="urn:orders" xmlns:a="urn:orders" a:id="42"><code>AB1</code></Request></s:Body></s:Envelope>""",
        parsedExample: Boolean = false
    ) {
        val fixture = WSDLNamespaceFixture("urn:orders", "/namespaces")
        val declarations = """<xs:element name="Request"><xs:complexType><xs:sequence>
            <xs:element name="code" type="xs:string"/></xs:sequence>
            <xs:attribute name="id" type="xs:string" use="required"/></xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>"""
        val feature = parseContractFileToFeature(directory.resolve("examples.wsdl").apply {
            writeText(fixture.wsdl(fixture.schema(declarations, defaults)))
        })
        val scenario = feature.scenarios.single()
        val exampleValue = if (parsedExample) toXMLNode(parseXML(exampleBody)) else StringValue(exampleBody)
        val example = scenario.generateHttpRequest().copy(body = exampleValue)
        val response = scenario.generateHttpResponse().copy(headers = emptyMap(), body = StringValue("""
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body><o:Response xmlns:o="urn:orders">EXAMPLE_OK</o:Response></s:Body>
            </s:Envelope>
        """.trimIndent()))
        val incoming = example.copy(body = StringValue(body))
        HttpStub(feature, scenarioStubs = listOf(ScenarioStub(request = example, response = response)), port = testPort()).use { stub ->
            val actual = stub.client.execute(incoming)
            assertThat(actual.status).describedAs(description).isEqualTo(status)
            assertThat(actual.body.toStringLiteral().contains("EXAMPLE_OK")).describedAs(description).isEqualTo(matchesExample)
            if (matchesExample) assertThat(actual.headers["X-Specmatic-Type"]).isNotEqualTo("random")
        }
    }

    companion object {
        private fun envelope(payload: String, declarations: String = "", header: String = ""): String =
            """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:urn="urn:orders" $declarations>
                $header<soapenv:Body>$payload</soapenv:Body></soapenv:Envelope>"""

        @JvmStatic
        fun unqualifiedRequests(): List<Arguments> = listOf(
            Arguments.of("prefixed envelope needs no reset", envelope("""<urn:Request id="42"><code>AB1</code></urn:Request>"""), 200, true),
            Arguments.of("qualified child cannot match unqualified example", envelope("""<urn:Request id="42"><urn:code>AB1</urn:code></urn:Request>"""), 400, false),
            Arguments.of("qualified attribute cannot match unqualified example", envelope("""<urn:Request urn:id="42"><code>AB1</code></urn:Request>"""), 400, false)
        )

        @JvmStatic
        fun qualifiedRequests(): List<Arguments> = listOf(
            Arguments.of("equivalent prefixes", envelope("""<urn:Request urn:id="42"><urn:code>AB1</urn:code></urn:Request>"""), 200, true),
            Arguments.of("same prefix as example but wrong attribute namespace", envelope("""<urn:Request a:id="42"><urn:code>AB1</urn:code></urn:Request>""", """xmlns:a="urn:wrong""""), 400, false),
            Arguments.of("unqualified attribute", envelope("""<urn:Request id="42"><urn:code>AB1</urn:code></urn:Request>"""), 400, false),
            Arguments.of("unqualified child", envelope("""<urn:Request urn:id="42"><code>AB1</code></urn:Request>"""), 400, false),
            Arguments.of("wrong child namespace", envelope("""<urn:Request urn:id="42"><w:code>AB1</w:code></urn:Request>""", """xmlns:w="urn:wrong""""), 400, false),
            Arguments.of("wrong payload namespace", envelope("""<w:Request urn:id="42"><urn:code>AB1</urn:code></w:Request>""", """xmlns:w="urn:wrong""""), 400, false),
            Arguments.of("empty SOAP header", envelope("""<urn:Request urn:id="42"><urn:code>AB1</urn:code></urn:Request>""", header = "<soapenv:Header/>"), 200, true),
            Arguments.of("wrong namespace on empty header", envelope("""<urn:Request urn:id="42"><urn:code>AB1</urn:code></urn:Request>""", """xmlns:w="urn:wrong"""", "<w:Header/>"), 400, false),
            Arguments.of("different child value generates a response", envelope("""<urn:Request urn:id="42"><urn:code>OTHER</urn:code></urn:Request>"""), 200, false),
            Arguments.of("different attribute value generates a response", envelope("""<urn:Request urn:id="43"><urn:code>AB1</urn:code></urn:Request>"""), 200, false)
        )
    }
}
