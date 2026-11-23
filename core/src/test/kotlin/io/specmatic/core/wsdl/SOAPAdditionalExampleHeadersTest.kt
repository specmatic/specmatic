package io.specmatic.core.wsdl

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.StringValue
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

class SOAPAdditionalExampleHeadersTest {
    @TempDir
    lateinit var directory: File

    @ParameterizedTest
    @CsvSource("1.1,true,true", "1.1,true,false", "1.1,false,true", "1.2,true,true", "1.2,true,false", "1.2,false,true")
    fun `external examples run through the real mock and test paths independently in each direction`(
        version: String, requestExtra: Boolean, responseExtra: Boolean
    ) {
        val example = example(if (requestExtra) "request-value" else null, if (responseExtra) "response-value" else null, version)
        val fixture = fixture(listOf(example), version = version)
        fixture.feature.validateExamplesOrException()
        val requests = mutableListOf<HttpRequest>()
        val responses = mutableListOf<HttpResponse>()
        HttpStub(fixture.feature, fixture.scenarioStubs, port = testPort()).use { mock ->
            val results = fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    requests.add(request)
                    return mock.client.execute(request).also { responses.add(it) }
                }
            })
            assertThat(results.success()).withFailMessage(results.report()).isTrue()
        }
        assertThat(requests).isNotEmpty()
        requests.forEach { assertAdditional(it.body.toStringLiteral(), if (requestExtra) "request-value" else null) }
        responses.forEach { assertAdditional(it.body.toStringLiteral(), if (responseExtra) "response-value" else null) }
    }

    @Test
    fun `competing examples select by additional XML values and namespaces without admitting unrelated blocks`() {
        val first = example("first", "first-response")
        val second = example("second", "second-response")
        val noOptIn = example(null, null).copy(request = headerRequest(envelope("").replace(">hello</t:Request>", ">plain</t:Request>")))
        val fixture = fixture(listOf(first, second, noOptIn))
        HttpStub(fixture.feature, fixture.scenarioStubs, port = testPort()).use { mock ->
            listOf(first, second).forEach { authored ->
                val request = authored.request.copy(body = StringValue(authored.request.body.toStringLiteral()
                    .replace("xmlns:x=", "xmlns:other=").replace("x:", "other:")))
                val actual = mock.client.execute(request)
                assertThat(actual.status).isEqualTo(200)
                assertThat(actual.headers["X-Specmatic-Type"]).isNotEqualTo("random")
                assertAdditional(actual.body.toStringLiteral(), if (authored == first) "first-response" else "second-response")
            }
            val incorrect = listOf(
                first.request.body.toStringLiteral().replace(">first</x:Value>", ">wrong</x:Value>"),
                first.request.body.toStringLiteral().replace("urn:example-extra", "urn:wrong-extra"),
                first.request.body.toStringLiteral().replace("</s:Header>", "<x:Other xmlns:x=\"urn:example-extra\">other</x:Other></s:Header>"),
                first.request.body.toStringLiteral().replace(">hello</t:Request>", ">plain</t:Request>"),
            )
            incorrect.forEach { xml ->
                val actual = mock.client.execute(headerRequest(xml))
                assertThat(actual.status).withFailMessage(actual.body.toStringLiteral()).isEqualTo(400)
            }
            val plain = mock.client.execute(noOptIn.request)
            assertThat(plain.status).isEqualTo(200)
            assertAdditional(plain.body.toStringLiteral(), null)
            val missing = mock.client.execute(headerRequest(envelope("")))
            assertThat(missing.body.toStringLiteral()).doesNotContain("first-response", "second-response")
            val results = fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = mock.client.execute(request)
            })
            assertThat(results.success()).withFailMessage(results.report()).isTrue()
        }
    }

    @ParameterizedTest
    @CsvSource("missing", "changed", "namespace", "nested-namespace", "attribute")
    fun `contract test rejects a missing or changed additional response expectation`(change: String) {
        val authored = example(null, "expected")
        val fixture = fixture(listOf(authored))
        val response = when (change) {
            "missing" -> soapResponse("")
            "changed" -> example(null, "changed").response
            "namespace" -> authored.response.copy(body = StringValue(authored.response.body.toStringLiteral().replace("urn:example-extra", "urn:wrong-extra")))
            "nested-namespace" -> authored.response.copy(body = StringValue(authored.response.body.toStringLiteral().replace("<x:Value>", "<x:Value xmlns:x=\"urn:wrong-extra\">")))
            else -> authored.response.copy(body = StringValue(authored.response.body.toStringLiteral().replace("kind=\"literal\"", "kind=\"changed\"")))
        }
        val results = fixture.feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse = response
        })
        assertThat(results.success()).withFailMessage(results.report()).isFalse()
        assertThat(results.report()).contains("RESPONSE", "Header")
    }

    @Test
    fun `response expectations keep declared values schema based and use response declarations`() {
        val request = headerRequest(envelope("<s:Header><t:Receipt><t:id>request-extra</t:id></t:Receipt></s:Header>"))
        val response = soapResponse("<s:Header><t:Receipt><t:id>7</t:id></t:Receipt>${block("expected")}</s:Header>")
        val fixture = fixture(listOf(ScenarioStub(request = request, response = response)))
        fixture.feature.validateExamplesOrException()
        listOf("8" to true, "invalid" to false).forEach { (value, accepted) ->
            val actual = response.copy(body = StringValue(response.body.toStringLiteral().replace(">7</t:id>", ">$value</t:id>")))
            val results = fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = actual
            })
            assertThat(results.success()).withFailMessage(results.report()).isEqualTo(accepted)
        }
    }

    @Test
    fun `additional blocks never relax declared content requiredness or Body validation during mock loading`() {
        val validRequest = "<t:Session><t:id>7</t:id></t:Session>"
        val validResponse = "<t:Receipt><t:id>7</t:id></t:Receipt>"
        val feature = headerFeature(claimedHeaderWsdl())
        val authored = ScenarioStub(
            request = headerRequest(envelope("<s:Header>$validRequest${block("request")}</s:Header>")),
            response = soapResponse("<s:Header>$validResponse${block("response")}</s:Header>"),
        )
        val invalid = listOf(
            authored.copy(request = authored.request.copy(body = StringValue(authored.request.body.toStringLiteral().replace(validRequest, "")))),
            authored.copy(request = authored.request.copy(body = StringValue(authored.request.body.toStringLiteral().replace(">7</t:id>", ">invalid</t:id>")))),
            authored.copy(response = authored.response.copy(body = StringValue(authored.response.body.toStringLiteral().replace(validResponse, "")))),
            authored.copy(response = authored.response.copy(body = StringValue(authored.response.body.toStringLiteral().replace(">7</t:id>", ">invalid</t:id>")))),
            authored.copy(request = authored.request.copy(body = StringValue(authored.request.body.toStringLiteral().replace("<t:Request>", "<t:Request xmlns:t=\"urn:wrong-body\">")))),
            authored.copy(response = authored.response.copy(body = StringValue(authored.response.body.toStringLiteral().replace("<t:Response>", "<t:Response xmlns:t=\"urn:wrong-body\">")))),
            authored.copy(request = authored.request.copy(body = StringValue(authored.request.body.toStringLiteral().replace("</s:Body>", "<t:Other>extra</t:Other></s:Body>")))),
        )
        invalid.forEach { bad -> assertThrows<NoMatchingScenario> { HttpStub(feature, listOf(bad), port = testPort()).use { } } }
        HttpStub(feature, listOf(authored), port = testPort()).use { mock ->
            assertThat(mock.client.execute(authored.request).status).isEqualTo(200)
            invalid.filter { it.request != authored.request }.forEach { bad ->
                val actual = mock.client.execute(bad.request)
                assertThat(actual.status).withFailMessage(actual.body.toStringLiteral()).isEqualTo(400)
            }
        }
    }

    @Test
    fun `request opt in does not allow an extra response and response opt in does not allow an extra request`() {
        listOf(example("request", null), example(null, "response")).forEach { authored ->
            val fixture = fixture(listOf(authored))
            val requestOptIn = authored.request.body.toStringLiteral().contains("urn:example-extra")
            val unexpected = if (requestOptIn) {
                example(null, "unexpected").response
            } else authored.response
            val results = fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = unexpected
            })
            assertThat(results.success()).isEqualTo(!requestOptIn)
            if (!requestOptIn) {
                HttpStub(fixture.feature, fixture.scenarioStubs, port = testPort()).use { mock ->
                    assertThat(mock.client.execute(example("unexpected", null).request).status).isEqualTo(400)
                }
            }
        }
    }

    @Test
    fun `without declarations additional examples work but do not leak into ordinary matching or generation`() {
        val wsdl = headerWsdl().replace(Regex("<soap:header[^>]*/>"), "")
        val authored = example("request", "response")
        val fixture = fixture(listOf(authored), wsdl = wsdl)
        HttpStub(fixture.feature, fixture.scenarioStubs, port = testPort()).use { mock ->
            val result = fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = mock.client.execute(request)
            })
            assertThat(result.success()).withFailMessage(result.report()).isTrue()
        }
        val ordinary = headerFeature(wsdl)
        val scenario = ordinary.scenarios.single()
        HttpStub(ordinary, port = testPort()).use { mock ->
            assertThat(mock.client.execute(authored.request).status).isEqualTo(400)
        }
        listOf(scenario.httpRequestPattern.body, scenario.httpResponsePattern.body).forEach { pattern ->
            val variants = pattern.newBasedOn(io.specmatic.core.pattern.Row(), scenario.resolver).map { it.value }.toList() +
                pattern.newBasedOn(scenario.resolver).toList()
            variants.forEach { assertAdditional(it.generate(scenario.resolver).toStringLiteral(), null) }
        }
    }

    @Test
    fun `unqualified additional blocks are not opted in`() {
        val authored = example("request", "response")
        val bad = authored.copy(request = authored.request.copy(body = StringValue(authored.request.body.toStringLiteral()
            .replace("x:Trace", "Trace").replace("x:Value", "Value"))))
        assertThrows<NoMatchingScenario> { HttpStub(headerFeature(), listOf(bad), port = testPort()).use { } }
    }

    @Test
    fun `existing XML pattern tokens select mocks without weakening literal response blocks`() {
        val fixture = fixture(listOf(example("(number)", "response")))
        HttpStub(fixture.feature, fixture.scenarioStubs, port = testPort()).use { mock ->
            val accepted = mock.client.execute(example("42", null).request)
            assertThat(accepted.status).isEqualTo(200)
            assertAdditional(accepted.body.toStringLiteral(), "response")
            assertThat(mock.client.execute(example("not-a-number", null).request).status).isEqualTo(400)
        }
    }

    @Test
    fun `a same local name in another namespace cannot replace a required declared block`() {
        val declared = "<t:Session><t:id>7</t:id></t:Session>"
        val additional = block("additional").replace("x:Trace", "x:Session")
        val authored = ScenarioStub(
            request = headerRequest(envelope("<s:Header>$additional$declared</s:Header>")),
            response = soapResponse("<s:Header><t:Receipt><t:id>7</t:id></t:Receipt></s:Header>"),
        )
        val fixture = fixture(listOf(authored), wsdl = claimedHeaderWsdl())
        fixture.feature.validateExamplesOrException()
        HttpStub(fixture.feature, fixture.scenarioStubs, port = testPort()).use { mock ->
            assertThat(mock.client.execute(authored.request).status).isEqualTo(200)
            val missing = authored.request.copy(body = StringValue(authored.request.body.toStringLiteral().replace(declared, "")))
            assertThat(mock.client.execute(missing).status).isEqualTo(400)
        }
    }

    @Test
    fun `external example validation rejects invalid declared blocks alongside additional blocks`() {
        val authored = example("request", "response")
        val invalidRequest = authored.copy(request = authored.request.copy(body = StringValue(authored.request.body.toStringLiteral()
            .replace("</s:Header>", "<t:Session><t:id>invalid</t:id></t:Session></s:Header>"))))
        val invalidResponse = authored.copy(response = authored.response.copy(body = StringValue(authored.response.body.toStringLiteral()
            .replace("</s:Header>", "<t:Receipt><t:id>invalid</t:id></t:Receipt></s:Header>"))))
        listOf(invalidRequest, invalidResponse).forEach { bad ->
            val error = assertThrows<ContractException> { fixture(listOf(bad)).feature.validateExamplesOrException() }
            assertThat(error.message).contains("invalid", "id")
        }
    }

    @ParameterizedTest
    @CsvSource("urn:example-extra", "http://schemas.xmlsoap.org/soap/envelope/", "http://www.w3.org/2003/05/soap-envelope")
    fun `extra blocks named Body and Fault remain literal XML`(namespace: String) {
        fun asApplicationBody(xml: String) = xml.replace("x:Trace", "x:Body").replace("x:Value", "x:Fault")
            .replace("urn:example-extra", namespace)
        val raw = example("request", "response")
        val authored = raw.copy(
            request = raw.request.copy(body = StringValue(asApplicationBody(raw.request.body.toStringLiteral()))),
            response = raw.response.copy(body = StringValue(asApplicationBody(raw.response.body.toStringLiteral()))),
        )
        val fixture = fixture(listOf(authored))
        HttpStub(fixture.feature, fixture.scenarioStubs, port = testPort()).use { mock ->
            assertThat(mock.client.execute(authored.request).status).isEqualTo(200)
            val wrong = authored.request.copy(body = StringValue(authored.request.body.toStringLiteral().replace(">request</x:Fault>", ">wrong</x:Fault>")))
            assertThat(mock.client.execute(wrong).status).isEqualTo(400)
        }
        val results = fixture.feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse = authored.response.copy(
                body = StringValue(authored.response.body.toStringLiteral().replace(">response</x:Fault>", ">wrong</x:Fault>"))
            )
        })
        assertThat(results.success()).withFailMessage(results.report()).isFalse()
    }

    @ParameterizedTest
    @CsvSource("1.1", "1.2")
    fun `the existing SOAP fault shortcut is unchanged without example opt in`(version: String) {
        val fault = soapResponse("", version).let { response -> response.copy(body = StringValue(
            response.body.toStringLiteral().replace("<t:Response>hello</t:Response>", "<s:Fault><faultcode>s:Server</faultcode><faultstring>example</faultstring></s:Fault>")
        )) }
        val results = headerFeature(version = version).executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse = fault
        })
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    private fun fixture(examples: List<ScenarioStub>, version: String = "1.1", wsdl: String = headerWsdl()): WsdlExampleFixture {
        val folder = File(directory, "fixture-${directory.listFiles().orEmpty().size}").apply { mkdirs() }
        val contract = File(folder, "service.wsdl").apply {
            writeText(if (version == "1.2") wsdl.replace("http://schemas.xmlsoap.org/wsdl/soap/", "http://schemas.xmlsoap.org/wsdl/soap12/") else wsdl)
        }
        val examplesDir = File(folder, "service_examples").apply { mkdirs() }
        examples.forEachIndexed { index, example -> File(examplesDir, "$index.json").writeText(example.toJSON().toStringLiteral()) }
        return loadWsdlExampleFixture(contract.path, examplesDir.path)
    }

    private fun example(requestValue: String?, responseValue: String?, version: String = "1.1") = ScenarioStub(
        request = headerRequest(envelope(header(requestValue), version = version), version),
        response = soapResponse(header(responseValue), version),
    )

    private fun header(value: String?) = if (value == null) "" else "<s:Header>${block(value)}</s:Header>"

    private fun block(value: String) = """<x:Trace xmlns:x="urn:example-extra" kind="literal"><x:Value>$value</x:Value></x:Trace>"""

    private fun assertAdditional(xml: String, value: String?) {
        assertThat(soapElementCount(xml, "urn:example-extra", "Trace")).isEqualTo(if (value == null) 0 else 1)
        if (value != null) {
            assertThat(xml).contains(value, "kind=\"literal\"")
            assertThat(soapElementCount(xml, "urn:example-extra", "Value")).isEqualTo(1)
        }
    }
}
