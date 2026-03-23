package io.specmatic.conformance_test_support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI

data class HttpExchange(
    val method: String,
    val url: String,
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
            val requestHeaders: Map<String, String> = mapper.readValue(node[2].traverse())
            val responseHeaders: Map<String, String> = mapper.readValue(node[5].traverse())
            return HttpExchange(
                method = node[0].asText(),
                url = node[1].asText(),
                requestHeaders = requestHeaders,
                requestBody = node[3].asText(),
                statusCode = node[4].asInt(),
                responseHeaders = responseHeaders,
                responseBody = node[6].asText(),
            )
        }

        fun parseAll(text: String): List<HttpExchange> =
            text.lineSequence()
                .filter { it.isNotBlank() }
                .map(::parse)
                .toList()

        private fun Map<String, String>.contentType(): String? =
            entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
    }
}

private val PARAM_PLACEHOLDER = Regex("""\{[^}]+}""")

private fun String.toPathRegex(): Regex {
    val body = PARAM_PLACEHOLDER.split(trimEnd('/')).joinToString("[^/]+") { Regex.escape(it) }
    return Regex("^$body$")
}

fun List<HttpExchange>.toOperations(specOperations: Set<Operation>): Set<Operation> {
    val patterns = specOperations.associateWith { it.path.toPathRegex() }
    return map { exchange ->
        val method = exchange.method.uppercase()
        val path = URI(exchange.url).path.trimEnd('/')
        patterns.entries.find { (op, regex) -> op.method == method && regex.matches(path) }?.key
            ?: Operation(method, path)
    }.toSet()
}
