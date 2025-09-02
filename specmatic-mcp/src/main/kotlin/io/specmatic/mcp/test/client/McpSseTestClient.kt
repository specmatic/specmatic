package io.specmatic.mcp.test.client

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson
import io.ktor.sse.ServerSentEvent
import io.specmatic.mcp.test.ToolResponse
import io.specmatic.mcp.test.client.model.Capabilities
import io.specmatic.mcp.test.client.model.ClientInfo
import io.specmatic.mcp.test.client.model.InitializeParams
import io.specmatic.mcp.test.client.model.JsonRpcRequest
import io.specmatic.mcp.test.client.model.JsonRpcResponse
import io.specmatic.mcp.test.client.model.RootsCapability
import io.specmatic.mcp.test.client.model.Tool
import io.specmatic.mcp.test.client.model.ToolsListResult
import io.specmatic.mcp.test.logWithTag
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class McpSseTestClient(
    private val baseUrl: String = "http://localhost:8080",
    private val objectMapper: ObjectMapper = ObjectMapper()
): McpTestClient {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson {
                configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
        install(SSE)
    }

    private var sessionId: String? = null
    private val requestIdGenerator = AtomicLong(1)
    private var sseJob: Job? = null
    private val responseChannel = Channel<JsonRpcResponse>(Channel.UNLIMITED)

    private suspend fun establishSSEConnection() {
        val sessionIdDeferred = CompletableDeferred<String>()

        sseJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                client.sse(
                    urlString = "$baseUrl/mcp",
                    request = {
                        headers {
                            append("Accept", "text/event-stream")
                            append("Cache-Control", "no-cache")
                        }
                    }
                ) {
                    logWithTag("SSE connection established, collecting events...")

                    incoming.collect { event ->
                        try {
                            handleSSEEvent(event, sessionIdDeferred)
                        } catch (e: Exception) {
                            logWithTag("Error handling SSE event: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logWithTag("SSE connection error: ${e.message}")
                if (!sessionIdDeferred.isCompleted) {
                    sessionIdDeferred.completeExceptionally(e)
                }
            }
        }

        // Wait for session ID to be extracted
        try {
            sessionId = withTimeout(10000) { // 10 second timeout
                sessionIdDeferred.await()
            }
            logWithTag("Session ID established: $sessionId")
        } catch (e: Exception) {
            sseJob?.cancel()
            throw IllegalStateException("Could not establish session ID from SSE connection: ${e.message}")
        }
    }

    private fun handleSSEEvent(event: ServerSentEvent, sessionIdDeferred: CompletableDeferred<String>) {
        when (event.event) {
            "endpoint" -> {
                val data = event.data ?: return
                logWithTag("SSE Endpoint Event: $data")

                // Extract session ID from endpoint data
                // Format: /messages/?session_id=70bd721fbcc949af86833f3e4e1e34aa
                val sessionIdRegex = """session_id=([a-f0-9-]+)""".toRegex()
                sessionIdRegex.find(data)?.groupValues?.get(1)?.let { extractedSessionId ->
                    logWithTag("Extracted session ID: $extractedSessionId")
                    if (!sessionIdDeferred.isCompleted) {
                        sessionIdDeferred.complete(extractedSessionId)
                    }
                }
            }
            "message" -> {
                val data = event.data ?: return
//                logWithTag("SSE Message Event: $data")

                // Parse JSON-RPC response
                try {
                    val jsonResponse = objectMapper.readValue(data, JsonRpcResponse::class.java)
                    responseChannel.trySend(jsonResponse)
                } catch (e: Exception) {
                    logWithTag("Failed to parse JSON-RPC response: ${e.message}")
                }
            }
            null -> {
                // Handle events without explicit type (like ping comments)
                if (event.data?.startsWith("ping") == true) {
                    logWithTag("SSE Ping: ${event.data}")
                }
            }
            else -> {
                logWithTag("Unknown SSE event: ${event.event}, data: ${event.data}")
            }
        }
    }

    private suspend fun sendJsonRpcRequest(request: JsonRpcRequest): JsonRpcResponse {
        logWithTag("Sending request: ${objectMapper.writeValueAsString(request)}")

        val response = client.post("$baseUrl/messages/") {
            parameter("session_id", sessionId)
            contentType(ContentType.Application.Json)
            headers {
                append(HttpHeaders.Accept, "application/json, text/event-stream")
            }
            setBody(request)
        }

        logWithTag("HTTP Response Status: ${response.status}")

        // The actual response comes through SSE, wait for it
        return if (request.id != null) {
            withTimeout(10000) { // 10 second timeout
                val jsonRpcResponse = responseChannel.receive()
                logWithTag("Received response: ${objectMapper.writeValueAsString(jsonRpcResponse)}")
                jsonRpcResponse
            }
        } else {
            // For notifications, return a dummy response
            JsonRpcResponse(jsonrpc = "2.0", id = null)
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

        sendJsonRpcRequest(request)
    }

    override suspend fun tools(): List<Tool> {
        val request = JsonRpcRequest(
            id = requestIdGenerator.getAndIncrement(),
            method = "tools/list"
        )

        val response = sendJsonRpcRequest(request)

        return if (response.result != null) {
            val toolsResult = objectMapper.treeToValue(response.result, ToolsListResult::class.java)
            toolsResult.tools
        } else {
            emptyList()
        }
    }

    override suspend fun toolCall(toolName: String, arguments: Map<String, Any?>): JsonRpcResponse {
        val request = JsonRpcRequest(
            id = requestIdGenerator.getAndIncrement(),
            method = "tools/call",
            params = mapOf("name" to toolName, "arguments" to arguments)
        )

        val response = sendJsonRpcRequest(request)

        return response
    }

    override suspend fun connect(): McpTestClient {
        establishSSEConnection()
        val initResponse = initialize()
        if (initResponse.error != null) {
            logWithTag("Initialization failed: ${initResponse.error}")
            throw IllegalStateException("Initialization failed: ${initResponse.error}")
        }
        sendInitializedNotification()
        return this
    }

    override fun close() {
        sseJob?.cancel()
        responseChannel.close()
        client.close()
    }
}
