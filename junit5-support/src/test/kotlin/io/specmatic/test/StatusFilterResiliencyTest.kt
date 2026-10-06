package io.specmatic.test

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

class StatusFilterResiliencyTest {
    @Test
    fun `exact 429 filter runs only its example with resiliency enabled`(@TempDir directory: File) {
        val calls = runOrdersTests(directory, statusFilter = "429", resiliency = "all")
        assertThat(calls.map { it.query }).containsExactly("id=2")
    }

    @Test
    fun `4xx filter runs explicit error examples and generated negatives`(@TempDir directory: File) {
        val calls = runOrdersTests(directory, statusFilter = "4xx", resiliency = "all")
        assertThat(calls.map { it.status }).isNotEmpty().containsOnly(429)
        assertThat(calls.map { it.query }).contains("id=2").anyMatch { it != "id=2" }
    }

    @Test
    fun `endpoint filter runs examples and resiliency tests from the success response`(@TempDir directory: File) {
        val calls = runOrdersTests(directory, resiliency = "all")
        assertThat(calls.map { it.query }).contains("id=1", "id=2")
        assertThat(calls.filter { it.status == 200 }).hasSizeGreaterThan(1)
        assertThat(calls.filter { it.status == 429 && it.query != "id=2" }).isNotEmpty()
        assertThat(calls.filter { it.status == 429 && it.query == "id=2" }).hasSize(1)
    }

    @Test
    fun `endpoint filter runs only examples with resiliency disabled`(@TempDir directory: File) {
        val calls = runOrdersTests(directory, resiliency = "none")
        assertThat(calls.map { it.query }).containsExactlyInAnyOrder("id=1", "id=2")
        assertThat(calls.map { it.status }).containsExactlyInAnyOrder(200, 429)
    }

    @Test
    fun `exact 429 filter runs only its example with resiliency disabled`(@TempDir directory: File) {
        val calls = runOrdersTests(directory, statusFilter = "429", resiliency = "none")
        assertThat(calls.map { it.query }).containsExactly("id=2")
    }

    @Test
    fun `429 schema without an example runs no tests when resiliency is disabled`(@TempDir directory: File) {
        val calls = runOrdersTests(directory, statusFilter = "429", resiliency = "none", include429Example = false)
        assertThat(calls).isEmpty()
    }

    @Test
    fun `4xx filter runs only explicit error examples with resiliency disabled`(@TempDir directory: File) {
        val calls = runOrdersTests(directory, statusFilter = "4xx", resiliency = "none")
        assertThat(calls.map { it.query }).containsExactly("id=2")
        assertThat(calls.map { it.status }).containsExactly(429)
    }

    @Test
    fun `exact 400 filter excludes generated negatives even when they accept 400`(@TempDir directory: File) {
        val calls = runOrdersTests(directory, statusFilter = "400", resiliency = "all", errorStatus = 400)
        assertThat(calls.map { it.query }).containsExactly("id=2")
        assertThat(calls.map { it.status }).containsExactly(400)
    }

    private data class Call(val query: String, val status: Int)

    private fun runOrdersTests(
        directory: File,
        statusFilter: String? = null,
        resiliency: String,
        include429Example: Boolean = true,
        errorStatus: Int = 429
    ): List<Call> {
        val calls = CopyOnWriteArrayList<Call>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/orders") { exchange ->
            val query = URLDecoder.decode(exchange.requestURI.rawQuery.orEmpty(), "UTF-8")
            val parameters = query.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
            val id = parameters["id"]?.toIntOrNull()
            val expedited = parameters["expedited"]
            val invalid = id == null || (expedited != null && expedited !in listOf("true", "false"))
            val status = if (invalid || id == 2) errorStatus else 200
            calls.add(Call(query, status))
            val body = "\"ok\"".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val spec = directory.resolve("orders.yaml").apply {
                val definition = ordersSpec().replace("'429'", "'$errorStatus'")
                writeText(if (include429Example) definition else definition.substringBeforeLast("examples:").trimEnd())
            }
            val config = directory.resolve("specmatic.yaml").apply {
                writeText("""
                    version: 3
                    specmatic:
                      settings:
                        test:
                          schemaResiliencyTests: $resiliency
                """.trimIndent())
            }
            SpecmaticJUnitSupport.settingsStaging.set(ContractTestSettings(
                testBaseURL = "http://localhost:${server.address.port}",
                contractPaths = spec.canonicalPath,
                configFile = config.canonicalPath,
                filter = "METHOD='POST' && PATH='/orders'" + (statusFilter?.let { " && STATUS='$it'" } ?: "")
            ))
            val tests = SpecmaticJUnitSupport().contractTest().toList()
            if (include429Example) {
                tests.forEach { it.executable.execute() }
            } else {
                assertThat(tests).hasSize(1)
                val error = assertThrows(AssertionError::class.java) { tests.single().executable.execute() }
                assertThat(error.message).contains("No tests found to run")
            }
            return calls.toList()
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
            server.stop(0)
        }
    }

    private fun ordersSpec(): String = """
        openapi: 3.0.3
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders:
            post:
              parameters:
                - name: id
                  in: query
                  required: true
                  schema:
                    type: integer
                  examples:
                    SUCCESS:
                      value: 1
                    THROTTLED:
                      value: 2
                - name: expedited
                  in: query
                  required: false
                  schema:
                    type: boolean
              responses:
                '200':
                  description: Success
                  content:
                    application/json:
                      schema:
                        type: string
                      examples:
                        SUCCESS:
                          value: ok
                '429':
                  description: Throttled
                  content:
                    application/json:
                      schema:
                        type: string
                      examples:
                        THROTTLED:
                          value: ok
    """.trimIndent()
}
