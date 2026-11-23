package io.specmatic.core.wsdl

import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.Row

import io.specmatic.conversions.wsdlContentToFeature
import io.specmatic.core.HttpRequest
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.Arguments
import io.specmatic.core.HttpResponse
import io.specmatic.core.Feature
import java.io.File

class SOAPHeaderPresenceTest {
    @Test
    fun `a declared SOAP header can be omitted without a profile claim`() {
        val feature = wsdlContentToFeature(File("src/test/resources/wsdl/state_machine/header_part.wsdl").readText(), "headers.wsdl")
        val request = HttpRequest(
            method = "POST", path = "/header-part",
            headers = mapOf("SOAPAction" to "/header-part/operation", "Content-Type" to "text/xml"),
            body = StringValue("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:t="http://specmatic.io/header-part"><s:Body><t:HeaderPartRequest>hello</t:HeaderPartRequest></s:Body></s:Envelope>""")
        )
        val result = feature.scenarios.single().httpRequestPattern.matches(request, feature.scenarios.single().resolver)
        assertThat(result).withFailMessage(result.reportString()).isInstanceOf(Result.Success::class.java)
    }
    @Test
    fun `both derivation paths exercise optional headers without generating empty wrappers`() {
        val scenario = wsdlContentToFeature(File("src/test/resources/wsdl/soap_header_presence/service.wsdl").readText(), "headers.wsdl").scenarios.single()
        val body = scenario.httpRequestPattern.body
        val paths = listOf(
            body.newBasedOn(io.specmatic.core.pattern.Row(), scenario.resolver).map { it.value }.toList(),
            body.newBasedOn(scenario.resolver).toList()
        )
        paths.forEach { variants ->
            val messages = variants.map { it.generate(scenario.resolver).toStringLiteral() }
            assertThat(messages).anySatisfy { assertThat(soapElementCount(it, "urn:header-presence", "Session")).isEqualTo(1) }
            assertThat(messages).anySatisfy { assertThat(soapElementCount(it, "http://schemas.xmlsoap.org/soap/envelope/", "Header")).isZero() }
            assertThat(messages.count { soapElementCount(it, "http://schemas.xmlsoap.org/soap/envelope/", "Header") == 0 }).isEqualTo(1)
            messages.forEach { message ->
                assertThat(soapElementCount(message, "http://schemas.xmlsoap.org/soap/envelope/", "Header"))
                    .isEqualTo(soapElementCount(message, "urn:header-presence", "Session"))
                assertThat(body.matches(body.parse(message, scenario.resolver), scenario.resolver).isSuccess()).isTrue()
            }
        }
    }

    @Test
    fun `SOAP header control metadata is absent from XML pattern attributes and generated messages`() {
        fun assertNoControlAttributes(body: io.specmatic.core.pattern.Pattern) {
            if (body !is XMLPattern) return
            assertThat(body.pattern.attributes.keys).doesNotContain(
                "specmatic_omit_empty_soap_header", "specmatic_soap_header_required_reason"
            )
            body.pattern.nodes.forEach(::assertNoControlAttributes)
        }
        listOf(headerWsdl(), claimedHeaderWsdl()).forEach { text ->
            val scenario = headerFeature(text).scenarios.single()
            listOf(scenario.httpRequestPattern.body, scenario.httpResponsePattern.body).forEach { body ->
                assertNoControlAttributes(body)
                val variants = body.newBasedOn(Row(), scenario.resolver).map { it.value }.toList() +
                    body.newBasedOn(scenario.resolver).toList()
                variants.forEach { variant ->
                    assertNoControlAttributes(variant)
                    assertThat(variant.generate(scenario.resolver).toStringLiteral()).doesNotContain(
                        "specmatic_omit_empty_soap_header", "specmatic_soap_header_required_reason"
                    )
                }
            }
        }
    }

    @ParameterizedTest(name = "SOAP {0} {1}: {2}")
    @MethodSource("optionalMessages")
    fun `optional block presence is independent of schema validation`(version: String, direction: String, description: String, header: String, accepted: Boolean) {
        val scenario = headerFeature(version = version).scenarios.single()
        val xml = envelope(header, direction, version)
        val result = if (direction == "request")
            scenario.httpRequestPattern.matches(headerRequest(xml, version), scenario.resolver)
        else scenario.httpResponsePattern.matchesResponse(HttpResponse(200, headers = mapOf("Content-Type" to if (version == "1.1") "text/xml" else "application/soap+xml"), body = StringValue(xml)), scenario.resolver)
        assertThat(result.isSuccess()).withFailMessage(result.reportString()).isEqualTo(accepted)
    }

    @ParameterizedTest
    @MethodSource("soapVersions")
    fun `empty wrappers without declarations are allowed but undeclared blocks remain rejected`(version: String) {
        val text = headerWsdl().replace(Regex("<soap:header[^>]*/>"), "")
        val scenario = headerFeature(text, version).scenarios.single()
        listOf("", "<s:Header/>").forEach { header ->
            val result = scenario.httpRequestPattern.matches(headerRequest(envelope(header, "request", version), version), scenario.resolver)
            assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
        }
        val unexpected = scenario.httpRequestPattern.matches(headerRequest(envelope("<s:Header><t:Extra>inert</t:Extra></s:Header>", "request", version), version), scenario.resolver)
        assertThat(unexpected.isSuccess()).isFalse()
    }

    @Test
    fun `binding minOccurs and port type header shortcuts do not establish requiredness`() {
        val text = headerWsdl().replace("<soap:header ", "<soap:header minOccurs=\"1\" ")
            .replace("<wsdl:input message=\"tns:RequestMessage\"/>", """<wsdl:input message="tns:RequestMessage"><header part="session" required="true"/></wsdl:input>""")
        val scenario = headerFeature(text).scenarios.single()
        val result = scenario.httpRequestPattern.matches(headerRequest(envelope("")), scenario.resolver)
        assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
    }

    @Test
    fun `mixed SOAP header representations require the mandatory block without weakening optional content`() {
        val headers = io.specmatic.core.wsdl.payload.RequestHeaders(mapOf(
            "Required" to io.specmatic.core.wsdl.payload.RequestHeaders.HeaderDetails("(string)", io.specmatic.core.pattern.NodeOccurrence.Once),
            "Optional" to io.specmatic.core.wsdl.payload.RequestHeaders.HeaderDetails("(number)", io.specmatic.core.pattern.NodeOccurrence.Optional),
        ))
        val pattern = io.specmatic.core.pattern.XMLPattern(io.specmatic.core.wsdl.payload.soapMessage(
            io.specmatic.core.value.toXMLNode("<Payload>hello</Payload>"), emptyMap(), headers
        ), isSOAP = true)
        val resolver = io.specmatic.core.Resolver()
        fun message(header: String) = io.specmatic.core.value.toXMLNode("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">$header<s:Body><Payload>hello</Payload></s:Body></s:Envelope>""")
        listOf("", "<s:Header/>", "<s:Header><Optional>7</Optional></s:Header>").forEach {
            assertThat(pattern.matches(message(it), resolver).isSuccess()).isFalse()
        }
        assertThat(pattern.matches(message("<s:Header><Required>present</Required></s:Header>"), resolver).isSuccess()).isTrue()
        assertThat(pattern.matches(message("<s:Header><Required>present</Required><Optional>wrong</Optional></s:Header>"), resolver).isSuccess()).isFalse()
    }

    @ParameterizedTest(name = "empty SOAP Body {0} {1}, claim={2}, {3}")
    @MethodSource("headerOnlyMessages")
    fun `empty SOAP bodies retain declared header presence and content rules`(version: String, direction: String, claimed: Boolean, description: String, header: String, accepted: Boolean) {
        val wsdl = (if (claimed) claimedHeaderWsdl() else headerWsdl())
            .replace("""<wsdl:part name="body" element="tns:Request"/>""", "")
            .replace("""<wsdl:part name="body" element="tns:Response"/>""", "")
        val scenario = headerFeature(wsdl, version).scenarios.single()
        val element = if (direction == "request") "Request" else "Response"
        val xml = envelope(header, direction, version).replace("<t:$element>hello</t:$element>", "")
        val result = if (direction == "request")
            scenario.httpRequestPattern.matches(headerRequest(xml, version), scenario.resolver)
        else scenario.httpResponsePattern.matchesResponse(
            HttpResponse(200, headers = mapOf("Content-Type" to if (version == "1.1") "text/xml" else "application/soap+xml"), body = StringValue(xml)), scenario.resolver
        )
        assertThat(result.isSuccess()).withFailMessage(result.reportString()).isEqualTo(accepted)
    }

    @ParameterizedTest
    @MethodSource("soapVersions")
    fun `generated header-only SOAP messages retain declarations and omit unselected wrappers`(version: String) {
        val wsdl = headerWsdl()
            .replace("""<wsdl:part name="body" element="tns:Request"/>""", "")
            .replace("""<wsdl:part name="body" element="tns:Response"/>""", "")
        val scenario = headerFeature(wsdl, version).scenarios.single()
        val soapNamespace = if (version == "1.1") "http://schemas.xmlsoap.org/soap/envelope/" else "http://www.w3.org/2003/05/soap-envelope"
        listOf(scenario.httpRequestPattern.body to "Session", scenario.httpResponsePattern.body to "Receipt").forEach { (body, block) ->
            val paths = listOf(
                body.newBasedOn(io.specmatic.core.pattern.Row(), scenario.resolver).map { it.value }.toList(),
                body.newBasedOn(scenario.resolver).toList(),
            )
            paths.forEach { variants ->
                val messages = variants.map { it.generate(scenario.resolver) }
                assertThat(messages).anySatisfy { assertThat(soapElementCount(it.toStringLiteral(), "urn:header-presence", block)).isEqualTo(1) }
                assertThat(messages).anySatisfy { assertThat(soapElementCount(it.toStringLiteral(), soapNamespace, "Header")).isZero() }
                messages.forEach { message ->
                    val xml = message.toStringLiteral()
                    assertThat(soapElementCount(xml, soapNamespace, "Header")).isEqualTo(soapElementCount(xml, "urn:header-presence", block))
                    assertThat(soapElementCount(xml, soapNamespace, "Body")).isEqualTo(1)
                    assertThat(soapElementCount(xml, "urn:header-presence", "Request")).isZero()
                    assertThat(soapElementCount(xml, "urn:header-presence", "Response")).isZero()
                    assertThat(body.matches(message, scenario.resolver).isSuccess()).isTrue()
                }
            }
        }
    }

    companion object {
        @JvmStatic fun soapVersions() = listOf("1.1", "1.2")
        @JvmStatic fun headerOnlyMessages(): List<Arguments> = soapVersions().flatMap { version ->
            (if (version == "1.1") listOf(false, true) else listOf(false)).flatMap { claimed ->
                listOf("request", "response").flatMap { direction ->
                    val block = if (direction == "request") "Session" else "Receipt"
                    listOf(
                        Triple("omitted", "", !claimed),
                        Triple("empty wrapper", "<s:Header/>", !claimed),
                        Triple("valid block", "<s:Header><t:$block><t:id>7</t:id></t:$block></s:Header>", true),
                        Triple("invalid block", "<s:Header><t:$block><t:id>wrong</t:id></t:$block></s:Header>", false),
                    ).map { (description, header, accepted) -> Arguments.of(version, direction, claimed, description, header, accepted) }
                }
            }
        }
        @JvmStatic fun optionalMessages(): List<Arguments> = soapVersions().flatMap { version ->
            listOf("request", "response").flatMap { direction ->
                val block = if (direction == "request") "Session" else "Receipt"
                listOf(
                    Triple("omitted", "", true),
                    Triple("empty wrapper", "<s:Header/>", true),
                    Triple("valid block", "<s:Header><t:$block><t:id>7</t:id></t:$block></s:Header>", true),
                    Triple("invalid content", "<s:Header><t:$block><t:id>wrong</t:id></t:$block></s:Header>", false),
                    Triple("missing required child", "<s:Header><t:$block/></s:Header>", false),
                    Triple("wrong namespace", "<s:Header><$block xmlns=\"urn:wrong\"><id>7</id></$block></s:Header>", false),
                    Triple("undeclared block", "<s:Header><t:Extra>inert</t:Extra></s:Header>", false),
                ).map { (description, header, accepted) -> Arguments.of(version, direction, description, header, accepted) }
            }
        }
    }

}

internal fun headerWsdl(): String = File("src/test/resources/wsdl/soap_header_presence/service.wsdl").readText()
internal fun headerFeature(text: String = headerWsdl(), version: String = "1.1"): Feature =
    wsdlContentToFeature(if (version == "1.2") text.replace("http://schemas.xmlsoap.org/wsdl/soap/", "http://schemas.xmlsoap.org/wsdl/soap12/") else text, "headers.wsdl")
internal fun envelope(header: String, direction: String = "request", version: String = "1.1"): String {
    val namespace = if (version == "1.1") "http://schemas.xmlsoap.org/soap/envelope/" else "http://www.w3.org/2003/05/soap-envelope"
    val body = if (direction == "request") "Request" else "Response"
    return """<s:Envelope xmlns:s="$namespace" xmlns:t="urn:header-presence">$header<s:Body><t:$body>hello</t:$body></s:Body></s:Envelope>"""
}
internal fun headerRequest(xml: String, version: String = "1.1"): HttpRequest = HttpRequest(
    method = "POST", path = "/headers", headers = mapOf("SOAPAction" to "urn:exchange", "Content-Type" to if (version == "1.1") "text/xml" else "application/soap+xml"), body = StringValue(xml)
)
