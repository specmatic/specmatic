package io.specmatic.core.wsdl

import io.specmatic.core.HttpResponse
import io.specmatic.core.backwardCompatibilityRecords
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.pattern.Row
import org.junit.jupiter.api.io.TempDir
import java.io.File
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SOAPHeaderClaimTest {
    @Test
    fun `a Basic Profile 1_1 service instance claim requires declared request and response blocks`() {
        val scenario = headerFeature(claimedHeaderWsdl()).scenarios.single()
        listOf("", "<s:Header/>").forEach { header ->
            val requestResult = scenario.httpRequestPattern.matches(headerRequest(envelope(header)), scenario.resolver)
            assertThat(requestResult.isSuccess()).isFalse()
            assertThat(requestResult.reportString()).contains("Session", "Basic Profile 1.1", "Port", "input")
            val responseResult = scenario.httpResponsePattern.matchesResponse(
                HttpResponse(200, headers = mapOf("Content-Type" to "text/xml"), body = StringValue(envelope(header, "response"))), scenario.resolver
            )
            assertThat(responseResult.isSuccess()).isFalse()
            assertThat(responseResult.reportString()).contains("Receipt", "Basic Profile 1.1", "Port", "output")
        }
        val requestResult = scenario.httpRequestPattern.matches(headerRequest(envelope("<s:Header><t:Session><t:id>7</t:id></t:Session></s:Header>")), scenario.resolver)
        assertThat(requestResult.isSuccess()).withFailMessage(requestResult.reportString()).isTrue()
        val responseResult = scenario.httpResponsePattern.matchesResponse(
            HttpResponse(200, headers = mapOf("Content-Type" to "text/xml"), body = StringValue(envelope("<s:Header><t:Receipt><t:id>7</t:id></t:Receipt></s:Header>", "response"))), scenario.resolver
        )
        assertThat(responseResult.isSuccess()).withFailMessage(responseResult.reportString()).isTrue()
    }
    @Test
    fun `two ports sharing a binding retain independent request and response claim scope`() {
        val optional = """<wsdl:port name="OptionalPort" binding="tns:Binding"><soap:address location="http://localhost:9000/optional"/></wsdl:port>"""
        val claimed = """<wsdl:port name="ClaimedPort" binding="tns:Binding">${profileClaim()}<soap:address location="http://localhost:9000/claimed"/></wsdl:port>"""
        listOf(optional + claimed to true, claimed + optional to false).forEach { (ports, acceptsMissing) ->
            val text = headerWsdl().replace(Regex("<wsdl:service[\\s\\S]*?</wsdl:service>"), """<wsdl:service name="HeaderPresence">$ports</wsdl:service>""")
            val scenario = headerFeature(text).scenarios.single()
            val selectedPath = if (acceptsMissing) "/optional" else "/claimed"
            assertThat(scenario.path).isEqualTo(selectedPath)
            val request = headerRequest(envelope("")).copy(path = selectedPath)
            assertThat(scenario.httpRequestPattern.matches(request, scenario.resolver).isSuccess()).isEqualTo(acceptsMissing)
            assertThat(scenario.httpResponsePattern.matchesResponse(soapResponse(""), scenario.resolver).isSuccess()).isEqualTo(acceptsMissing)
        }
    }

    @Test
    fun `application extension names cannot select a different endpoint than the claimed WSDL port`() {
        val extension = """<app:service xmlns:app="urn:application:extensions" name="ExtensionService"><app:port name="ExtensionPort" binding="tns:Binding"><soap:address location="http://localhost:9000/extension"/></app:port></app:service>"""
        val text = claimedHeaderWsdl().replace("<wsdl:service name=\"HeaderPresence\">", "$extension<wsdl:service name=\"HeaderPresence\">")
        val scenario = headerFeature(text).scenarios.single()
        assertThat(scenario.path).isEqualTo("/headers")
        val result = scenario.httpRequestPattern.matches(headerRequest(envelope("")), scenario.resolver)
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).contains("Session", "Port", "Basic Profile 1.1")
        assertThat(scenario.httpResponsePattern.matchesResponse(soapResponse(""), scenario.resolver).isSuccess()).isFalse()
        val valid = scenario.httpRequestPattern.matches(headerRequest(envelope("<s:Header><t:Session><t:id>7</t:id></t:Session></s:Header>")), scenario.resolver)
        assertThat(valid.isSuccess()).withFailMessage(valid.reportString()).isTrue()
    }

    @Test
    fun `description claims and namespace lookalikes do not establish instance requiredness`() {
        val texts = listOf(
            headerWsdl().replace("""<wsdl:binding name="Binding" type="tns:PortType">""", """<wsdl:binding name="Binding" type="tns:PortType">${profileClaim()}"""),
            headerWsdl().replace("""<wsdl:portType name="PortType">""", """<wsdl:portType name="PortType">${profileClaim()}"""),
            headerWsdl().replaceFirst("""<wsdl:operation name="exchange">""", """<wsdl:operation name="exchange">${profileClaim()}"""),
            listOf("RequestMessage", "ResponseMessage", "SessionMessage", "ReceiptMessage").fold(headerWsdl()) { text, message ->
                text.replace("""<wsdl:message name="$message">""", """<wsdl:message name="$message">${profileClaim()}""")
            },
            claimedHeaderWsdl().replace("http://ws-i.org/schemas/conformanceClaim/", "urn:application:claims"),
            claimedHeaderWsdl().replace("<wsdl:documentation>", "<app:documentation xmlns:app=\"urn:application\">").replace("</wsdl:documentation>", "</app:documentation>"),
        )
        texts.forEach { text ->
            val scenario = headerFeature(text).scenarios.single()
            assertThat(scenario.httpRequestPattern.matches(headerRequest(envelope("")), scenario.resolver).isSuccess()).isTrue()
            assertThat(scenario.httpResponsePattern.matchesResponse(soapResponse(""), scenario.resolver).isSuccess()).isTrue()
        }
    }

    @Test
    fun `service instance claim applies through an imported binding with equivalent prefixes`(@TempDir directory: File) {
        val full = claimedHeaderWsdl()
        val start = full.indexOf("<wsdl:service")
        val end = full.indexOf("</wsdl:service>") + "</wsdl:service>".length
        directory.resolve("binding.wsdl").writeText(full.removeRange(start, end))
        val root = directory.resolve("service.wsdl").apply { writeText("""
            <w:definitions xmlns:w="http://schemas.xmlsoap.org/wsdl/" xmlns:b="urn:header-presence" xmlns:s="http://schemas.xmlsoap.org/wsdl/soap/" targetNamespace="urn:service">
                <w:import namespace="urn:header-presence" location="binding.wsdl"/>
                <w:service name="ImportedService"><w:port name="ImportedPort" binding="b:Binding">
                    ${profileClaim().replace("wsdl:", "w:")}
                    <s:address location="http://localhost:9000/headers"/>
                </w:port></w:service>
            </w:definitions>
        """) }
        val scenario = parseContractFileToFeature(root).scenarios.single()
        val result = scenario.httpRequestPattern.matches(headerRequest(envelope("")), scenario.resolver)
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).contains("ImportedPort", "service.wsdl", "Session")
        assertThat(scenario.httpResponsePattern.matchesResponse(soapResponse(""), scenario.resolver).isSuccess()).isFalse()
    }

    @Test
    fun `required headers survive both derivation overloads and ignore nonstandard occurrence overrides`() {
        val scenario = headerFeature(claimedHeaderWsdl().replace("<soap:header ", "<soap:header minOccurs=\"0\" ")).scenarios.single()
        listOf(scenario.httpRequestPattern.body to "Session", scenario.httpResponsePattern.body to "Receipt").forEach { (body, block) ->
            val variants = body.newBasedOn(Row(), scenario.resolver).map { it.value }.toList() + body.newBasedOn(scenario.resolver).toList()
            assertThat(variants).isNotEmpty()
            variants.forEach { variant ->
                val message = variant.generate(scenario.resolver)
                assertThat(soapElementCount(message.toStringLiteral(), "urn:header-presence", block)).isEqualTo(1)
                assertThat(body.matches(message, scenario.resolver).isSuccess()).isTrue()
                val direction = if (block == "Session") "request" else "response"
                val missing = variant.matches(variant.parse(envelope("", direction), scenario.resolver), scenario.resolver)
                assertThat(missing.isSuccess()).isFalse()
                assertThat(missing.reportString()).contains(block, "Basic Profile 1.1", "Port", "exchange")
            }
        }
    }

    @Test
    fun `compatibility detects request narrowing and response widening independently`() {
        fun withoutRequest(text: String) = text.replace(Regex("<soap:header message=\"tns:SessionMessage\"[^>]*/>"), "")
        fun withoutResponse(text: String) = text.replace(Regex("<soap:header message=\"tns:ReceiptMessage\"[^>]*/>"), "")
        val optionalRequest = headerFeature(withoutResponse(headerWsdl()))
        val requiredRequest = headerFeature(withoutResponse(claimedHeaderWsdl()))
        assertThat(backwardCompatibilityRecords(optionalRequest, requiredRequest).first.success()).isFalse()
        assertThat(backwardCompatibilityRecords(requiredRequest, optionalRequest).first.success()).isTrue()
        val optionalResponse = headerFeature(withoutRequest(headerWsdl()))
        val requiredResponse = headerFeature(withoutRequest(claimedHeaderWsdl()))
        assertThat(backwardCompatibilityRecords(requiredResponse, optionalResponse).first.success()).isFalse()
        assertThat(backwardCompatibilityRecords(optionalResponse, requiredResponse).first.success()).isTrue()
    }

}

internal fun profileClaim(profile: String = "http://ws-i.org/profiles/basic/1.1"): String =
    """<wsdl:documentation><claim:Claim xmlns:claim="http://ws-i.org/schemas/conformanceClaim/" conformsTo="$profile"/></wsdl:documentation>"""
internal fun claimedHeaderWsdl(): String = headerWsdl().replace(
    """<wsdl:port name="Port" binding="tns:Binding">""",
    """<wsdl:port name="Port" binding="tns:Binding">${profileClaim()}"""
)

internal fun soapResponse(header: String, version: String = "1.1") = HttpResponse(
    200, headers = mapOf("Content-Type" to if (version == "1.1") "text/xml" else "application/soap+xml"), body = StringValue(envelope(header, "response", version))
)
