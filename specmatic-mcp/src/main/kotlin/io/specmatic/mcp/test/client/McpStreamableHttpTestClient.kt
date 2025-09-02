package io.specmatic.mcp.test.client

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.specmatic.mcp.test.ToolResponse
import io.specmatic.mcp.test.client.model.Capabilities
import io.specmatic.mcp.test.client.model.ClientInfo
import io.specmatic.mcp.test.client.model.InitializeParams
import io.specmatic.mcp.test.client.model.JsonRpcRequest
import io.specmatic.mcp.test.client.model.JsonRpcResponse
import io.specmatic.mcp.test.client.model.RootsCapability
import io.specmatic.mcp.test.client.model.Tool
import io.specmatic.mcp.test.client.model.ToolsListResult
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import io.specmatic.core.log.logger
import io.specmatic.mcp.test.client.model.JsonRpcError
import io.specmatic.mcp.test.logWithTag

class McpStreamableHttpTestClient(
    private val baseUrl: String,
    private val bearerToken: String? = null,
    private val objectMapper: ObjectMapper = ObjectMapper()
): McpTestClient {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson {
                configure(FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
    }

    private var sessionId: String? = null
    private val requestIdGenerator = AtomicLong(1)

    private suspend fun sendJsonRpcRequest(request: JsonRpcRequest): JsonRpcResponse {
        logWithTag("Sending request: ${objectMapper.writeValueAsString(request)}")

        val httpResponse = client.post("$baseUrl/mcp") {
            contentType(ContentType.Application.Json)
            headers {
                append(HttpHeaders.Accept, "application/json, text/event-stream")
                sessionId?.let { append("mcp-session-id", it) }
                bearerToken?.let {
                    append(HttpHeaders.Authorization, "Bearer $it")
                }
            }
            setBody(request)
        }

        logWithTag("HTTP Response Status: ${httpResponse.status}")

        if (httpResponse.status == HttpStatusCode.InternalServerError) {
            return JsonRpcResponse(
                "2.0",
                null,
                error = JsonRpcError(HttpStatusCode.InternalServerError.value, "Internal Server Error")
            )
        }

        if (sessionId == null) {
            sessionId = httpResponse.headers["mcp-session-id"]
            logWithTag("Session ID extracted: $sessionId")
        }

        val responseText = httpResponse.bodyAsText()
        logWithTag("Response body: $responseText")

        return parseStreamableResponse(responseText)
    }

    private fun parseStreamableResponse(responseText: String): JsonRpcResponse {
        return when {
            responseText.startsWith("event: message") -> {
                val lines = responseText.split("\n")
                val dataLine = lines.find { it.startsWith("data: ") }
                    ?: throw IllegalStateException("No data line found in SSE response")

                val jsonData = dataLine.removePrefix("data: ")
                objectMapper.readValue(jsonData, JsonRpcResponse::class.java)
            }
            responseText.trim().startsWith("{") -> {
                objectMapper.readValue(responseText, JsonRpcResponse::class.java)
            }
            responseText.isBlank() -> {
                JsonRpcResponse("", null)
            }
            else -> {
                throw IllegalStateException("Unknown response format: $responseText")
            }
        }
    }

    override suspend fun initialize(): JsonRpcResponse {
        val initParams = InitializeParams(
            protocolVersion = "2024-11-05",
            capabilities = Capabilities(
                roots = RootsCapability(listChanged = true),
                sampling = emptyMap()
            ),
            clientInfo = ClientInfo(
                name = "test-client",
                version = "1.0.0"
            )
        )

        val request = JsonRpcRequest(
            id = requestIdGenerator.getAndIncrement(),
            method = "initialize",
            params = initParams
        )

        return sendJsonRpcRequest(request)
    }

    override suspend fun sendInitializedNotification() {
        val request = JsonRpcRequest(
            method = "notifications/initialized"
        )

        try {
            sendJsonRpcRequest(request)
            logWithTag("Initialized notification sent successfully")
        } catch (e: Exception) {
            logWithTag("Error sending initialized notification: ${e.message}")
        }
    }

    override suspend fun tools(): List<Tool> {
        if (sessionId == null) {
            throw IllegalStateException("Must initialize connection before calling tools/list")
        }

        val request = JsonRpcRequest(
            id = requestIdGenerator.getAndIncrement(),
            method = "tools/list"
        )

        val response = withTimeout(10000) {
            sendJsonRpcRequest(request)
        }

        return if (response.result != null) {
            val toolsResult = objectMapper.treeToValue(response.result, ToolsListResult::class.java)
            toolsResult.tools
        } else {
            logWithTag("No tools result in response: ${objectMapper.writeValueAsString(response)}")
            emptyList()
        }
    }

    override suspend fun toolCall(toolName: String, arguments: Map<String, Any?>): JsonRpcResponse {
        if (sessionId == null) {
            throw IllegalStateException("Must initialize connection before calling tools")
        }

        val request = JsonRpcRequest(
            id = requestIdGenerator.getAndIncrement(),
            method = "tools/call",
            params = mapOf("name" to toolName, "arguments" to arguments)
        )

        val response = withTimeout(30000) {
            sendJsonRpcRequest(request)
        }

        return response
    }

    override suspend fun connect(): McpStreamableHttpTestClient {
        val initResponse = initialize()
        logWithTag("Initialization response: ${objectMapper.writeValueAsString(initResponse)}")

        sendInitializedNotification()

        return this
    }

    override fun close() {
        client.close()
    }
}
