package io.specmatic.core.wsdl

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.backwardCompatibilityRecords
import io.specmatic.core.log.CompositePrinter
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.LogPrinter
import io.specmatic.core.log.NonVerbose
import io.specmatic.core.log.logger
import io.specmatic.core.log.withLogger
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/** Repeatable working demonstration using the current compiled OSS Specmatic runtime. */
class SOAPHeaderRuntimeDemonstrationTest {
    @Test
    fun `record SOAP header behavior through the running mock and contract test runtime`() {
        val directory = File("../temp/soap-header-evidence/runtime").apply { mkdirs() }
        val logFile = directory.resolve("specmatic.log").apply { writeText("") }
        val printer = object : LogPrinter {
            @Synchronized override fun print(msg: LogMessage, indentation: String) {
                logFile.appendText(indentation + msg.toLogString() + "\n")
            }
        }
        withLogger(NonVerbose(CompositePrinter(listOf(printer)))) {
            val sha = ProcessBuilder("git", "rev-parse", "HEAD").start().inputStream.bufferedReader().readText().trim()
            logger.log("SOURCE SHA: $sha")
            logger.log("RUNTIME: Gradle/JUnit; Java ${System.getProperty("java.version")}; Specmatic XMLPattern loaded from ${XMLPattern::class.java.protectionDomain.codeSource.location}")
            logger.log("This is an OSS runtime demonstration, not an Enterprise executable or Enterprise conformance check.")
            directory.resolve("no-claim.wsdl").writeText(headerWsdl())
            directory.resolve("claimed.wsdl").writeText(claimedHeaderWsdl())
            val optional = headerFeature()
            val required = headerFeature(claimedHeaderWsdl())
            demonstrateMock("no-claim", optional, directory, listOf(
                Triple("omitted", "", 200),
                Triple("empty", "<s:Header/>", 200),
                Triple("valid", "<s:Header><t:Session><t:id>7</t:id></t:Session></s:Header>", 200),
                Triple("invalid", "<s:Header><t:Session><t:id>wrong</t:id></t:Session></s:Header>", 400),
            ))
            demonstrateMock("claimed", required, directory, listOf(
                Triple("omitted", "", 400),
                Triple("empty", "<s:Header/>", 400),
                Triple("valid", "<s:Header><t:Session><t:id>7</t:id></t:Session></s:Header>", 200),
            ))
            logger.log("LOAD unsupported profile: the following warning must not block loading")
            val unsupportedText = claimedHeaderWsdl().replace("http://ws-i.org/profiles/basic/1.1", "urn:application:unsupported-profile")
            directory.resolve("unsupported.wsdl").writeText(unsupportedText)
            demonstrateMock("unsupported", headerFeature(unsupportedText), directory, listOf(Triple("omitted", "", 200)))

            listOf("no-claim" to optional, "claimed" to required).forEach { (policy, feature) ->
                val cases = listOf(
                    Triple("omitted", "", policy == "no-claim"),
                    Triple("empty", "<s:Header/>", policy == "no-claim"),
                    Triple("valid", "<s:Header><t:Receipt><t:id>7</t:id></t:Receipt></s:Header>", true),
                    Triple("invalid", "<s:Header><t:Receipt><t:id>wrong</t:id></t:Receipt></s:Header>", false),
                )
                cases.forEach { (label, header, accepted) ->
                    val response = soapResponse(header)
                    logger.log("RESPONSE VALIDATION $policy/$label: ${response.body.toStringLiteral()}")
                    val result = feature.executeTests(object : TestExecutor {
                        override fun execute(request: HttpRequest): HttpResponse = response
                    })
                    logger.log("RESPONSE VALIDATION $policy/$label: success=${result.success()}\n${result.report()}")
                    assertThat(result.success()).withFailMessage(result.report()).isEqualTo(accepted)
                }
                val scenario = feature.scenarios.single()
                listOf("request" to scenario.httpRequestPattern.body, "response" to scenario.httpResponsePattern.body).forEach { (direction, body) ->
                    val paths = listOf(
                        "row-and-resolver" to body.newBasedOn(Row(), scenario.resolver).map { it.value }.toList(),
                        "resolver-only" to body.newBasedOn(scenario.resolver).toList(),
                    )
                    paths.forEach { (path, variants) -> variants.forEachIndexed { index, variant ->
                        val message = variant.generate(scenario.resolver)
                        logger.log("GENERATED $policy/$direction/$path/$index: ${message.toStringLiteral()}")
                        assertThat(body.matches(message, scenario.resolver).isSuccess()).isTrue()
                        directory.resolve("generated-$policy-$direction-$path-$index.xml").writeText(message.toStringLiteral())
                    } }
                }
            }
            logger.log("COMPATIBILITY optional request -> required request")
            fun requestOnly(text: String) = headerFeature(text.replace(Regex("<soap:header message=\"tns:ReceiptMessage\"[^>]*/>"), ""))
            val narrowed = backwardCompatibilityRecords(requestOnly(headerWsdl()), requestOnly(claimedHeaderWsdl())).first
            logger.log("COMPATIBILITY request narrowing success=${narrowed.success()}\n${narrowed.report()}")
            assertThat(narrowed.success()).isFalse()
            val widened = backwardCompatibilityRecords(requestOnly(claimedHeaderWsdl()), requestOnly(headerWsdl())).first
            logger.log("COMPATIBILITY request widening success=${widened.success()}\n${widened.report()}")
            assertThat(widened.success()).isTrue()

            fun responseOnly(text: String) = headerFeature(text.replace(Regex("<soap:header message=\"tns:SessionMessage\"[^>]*/>"), ""))
            val widerResponses = backwardCompatibilityRecords(responseOnly(claimedHeaderWsdl()), responseOnly(headerWsdl())).first
            logger.log("COMPATIBILITY response widening success=${widerResponses.success()}\n${widerResponses.report()}")
            assertThat(widerResponses.success()).isFalse()
            val narrowerResponses = backwardCompatibilityRecords(responseOnly(headerWsdl()), responseOnly(claimedHeaderWsdl())).first
            logger.log("COMPATIBILITY response narrowing success=${narrowerResponses.success()}\n${narrowerResponses.report()}")
            assertThat(narrowerResponses.success()).isTrue()

            val fixture = loadWsdlExampleFixture("src/test/resources/wsdl/soap_header_presence/service.wsdl", "src/test/resources/wsdl/soap_header_presence/examples")
            HttpStub(fixture.feature, fixture.scenarioStubs, port = testPort(), log = { logger.log(it) }).use { mock ->
                val result = fixture.feature.executeTests(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        logger.log("EXPLICIT EMPTY HEADER EXAMPLE REQUEST: ${request.body.toStringLiteral()}")
                        assertThat(request.body.toStringLiteral()).contains("urn:application:example")
                        assertThat(soapElementCount(request.body.toStringLiteral(), "http://schemas.xmlsoap.org/soap/envelope/", "Header")).isEqualTo(1)
                        return mock.client.execute(request).also { logger.log("EXPLICIT EMPTY HEADER EXAMPLE RESPONSE: ${it.body.toStringLiteral()}") }
                    }
                })
                logger.log("EXPLICIT EMPTY HEADER EXAMPLE contract tests: success=${result.success()}\n${result.report()}")
                assertThat(result.success()).withFailMessage(result.report()).isTrue()
            }
        }
        assertThat(logFile.readText()).contains("SOURCE SHA:", "POST /headers", "Session", "RESPONSE VALIDATION", "GENERATED", "COMPATIBILITY", "WARNING")
    }

    private fun demonstrateMock(policy: String, feature: Feature, directory: File, cases: List<Triple<String, String, Int>>) {
        HttpStub(feature, port = testPort(), log = { logger.log(it) }).use { mock ->
            cases.forEach { (label, header, status) ->
                val request = headerRequest(envelope(header))
                directory.resolve("$policy-$label-request.xml").writeText(request.body.toStringLiteral())
                logger.log("MOCK REQUEST $policy/$label: ${request.body.toStringLiteral()}")
                val response = mock.client.execute(request)
                logger.log("MOCK RESULT $policy/$label: HTTP ${response.status}\n${response.body.toStringLiteral()}")
                assertThat(response.status).isEqualTo(status)
            }
            logger.log("GENERATED CONTRACT TESTS versus mock: $policy")
            val result = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    logger.log("CONTRACT TEST REQUEST: ${request.body.toStringLiteral()}")
                    return mock.client.execute(request)
                }
            })
            logger.log("GENERATED CONTRACT TESTS $policy: success=${result.success()}\n${result.report()}")
            assertThat(result.success()).withFailMessage(result.report()).isTrue()
        }
    }
}
