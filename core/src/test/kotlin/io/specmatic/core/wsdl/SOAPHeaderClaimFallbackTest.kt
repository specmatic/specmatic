package io.specmatic.core.wsdl

import io.specmatic.core.log.CompositePrinter
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.LogPrinter
import io.specmatic.core.log.NonVerbose
import io.specmatic.core.log.withLogger
import io.specmatic.core.parseContractFileToFeature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

class SOAPHeaderClaimFallbackTest {
    @TempDir lateinit var directory: File

    @ParameterizedTest(name = "strict={0}, {1}")
    @MethodSource("unsupportedClaims")
    fun `unrecognized or inapplicable claims load silently and retain the optional fallback`(strict: Boolean, kind: String, text: String, version: String) {
        val file = directory.resolve("unsupported.wsdl").apply { writeText(text) }
        val printer = ClaimLogPrinter()
        val feature = withLogger(NonVerbose(CompositePrinter(listOf(printer)))) {
            parseContractFileToFeature(file, strictMode = strict, lenientMode = !strict)
        }
        assertThat(printer.messages).isEmpty()
        val scenario = feature.scenarios.single()
        listOf("", "<s:Header/>").forEach { header ->
            val requestResult = scenario.httpRequestPattern.matches(headerRequest(envelope(header, version = version), version), scenario.resolver)
            assertThat(requestResult.isSuccess()).withFailMessage(requestResult.reportString()).isTrue()
            val responseResult = scenario.httpResponsePattern.matchesResponse(soapResponse(header, version), scenario.resolver)
            assertThat(responseResult.isSuccess()).withFailMessage(responseResult.reportString()).isTrue()
        }
    }

    @Test
    fun `unsupported unrelated claims do not cancel a supported applicable instance claim`() {
        val unknown = profileClaim("urn:application:unsupported-profile")
        val text = claimedHeaderWsdl().replace("<wsdl:service name=\"HeaderPresence\">", "<wsdl:service name=\"HeaderPresence\">$unknown")
        val file = directory.resolve("combined.wsdl").apply { writeText(text) }
        val printer = ClaimLogPrinter()
        val feature = withLogger(NonVerbose(CompositePrinter(listOf(printer)))) { parseContractFileToFeature(file, strictMode = true) }
        assertThat(printer.messages).isEmpty()
        val scenario = feature.scenarios.single()
        assertThat(scenario.httpRequestPattern.matches(headerRequest(envelope("")), scenario.resolver).isSuccess()).isFalse()
        assertThat(scenario.httpResponsePattern.matchesResponse(soapResponse(""), scenario.resolver).isSuccess()).isFalse()
    }

    companion object {
        @JvmStatic fun unsupportedClaims(): List<Arguments> = listOf(false, true).flatMap { strict ->
            val unknown = claimedHeaderWsdl().replace("http://ws-i.org/profiles/basic/1.1", "urn:application:other-profile")
            listOf(
                Arguments.of(strict, "unsupported profile", unknown, "1.1"),
                Arguments.of(strict, "unsupported attachment", headerWsdl().replace("<wsdl:service name=\"HeaderPresence\">", "<wsdl:service name=\"HeaderPresence\">${profileClaim()}"), "1.1"),
                Arguments.of(strict, "SOAP 1.2 applicability", claimedHeaderWsdl().replace("http://schemas.xmlsoap.org/wsdl/soap/", "http://schemas.xmlsoap.org/wsdl/soap12/"), "1.2"),
                Arguments.of(strict, "no declarations", unknown.replace(Regex("<soap:header[^>]*/>"), ""), "1.1"),
            )
        }
    }
}

internal class ClaimLogPrinter : LogPrinter {
    val messages = mutableListOf<String>()
    override fun print(msg: LogMessage, indentation: String) { messages.add(msg.toLogString()) }
}
