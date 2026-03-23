package io.specmatic.conformance_test_support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI

data class HttpExchange(
    val method: String,
    val url: String,
    val path: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val statusCode: Int,
    val responseHeaders: Map<String, String>,
    val responseBody: String,
) {
    companion object {
        private val mapper: ObjectMapper = jacksonObjectMapper()

        fun parse(line: String): HttpExchange {
            val node: JsonNode = mapper.readTree(line)
            return HttpExchange(
                method = node[0].asText(),
                url = node[1].asText(),
                path = URI(node[1].asText()).path.trimEnd('/').ifEmpty { "/" },
                requestHeaders = mapper.readValue<Map<String, String>>(node[2].traverse()),
                requestBody = node[3].asText(),
                statusCode = node[4].asInt(),
                responseHeaders = mapper.readValue<Map<String, String>>(node[5].traverse()),
                responseBody = node[6].asText(),
            )
        }

        fun parseAll(text: String): List<HttpExchange> =
            text.lineSequence()
                .filter { it.isNotBlank() }
                .map(::parse)
                .toList()
    }

    fun requestContentType(): String? =
        requestHeaders.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value

    fun responseContentType(): String? =
        responseHeaders.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value

    fun isInfraRequest(): Boolean {
        return when (method) {
            "HEAD" if path == "/" -> true
            "GET" if path == "/swagger/v1/swagger.yaml" -> true
            else -> false
        }
    }
}
