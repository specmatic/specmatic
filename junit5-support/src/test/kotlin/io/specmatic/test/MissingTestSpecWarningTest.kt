package io.specmatic.test

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress

class MissingTestSpecWarningTest {
    @AfterEach
    fun cleanup() {
        SpecmaticJUnitSupport.settingsStaging.remove()
    }

    @Test
    fun `should warn for missing spec in v2 provides while still loading existing spec`(@TempDir tempDir: File) {
        val existingSpec = tempDir.resolve("existing-spec.yaml")
        existingSpec.writeText(minimalOpenApiSpec())

        val configFile = tempDir.resolve("specmatic.yaml")
        configFile.writeText(
            """
            version: 2
            contracts:
              - filesystem:
                  directory: ${tempDir.canonicalPath}
                provides:
                  - existing-spec.yaml
                  - missing-spec.yaml
            """.trimIndent()
        )

        withLocalServer { baseUrl ->
            SpecmaticJUnitSupport.settingsStaging.set(
                ContractTestSettings(
                    configFile = configFile.canonicalPath,
                    testBaseURL = baseUrl
                )
            )

            val (output, tests) = captureStdOut {
                SpecmaticJUnitSupport().contractTest().toList()
            }

            assertThat(output).contains("WARNING: Skipping spec file missing-spec.yaml as it does not exist.")
            assertThat(tests).isNotEmpty
        }
    }

    @Test
    fun `should warn for missing spec in v3 systemUnderTest while still loading existing spec`(@TempDir tempDir: File) {
        val existingSpec = tempDir.resolve("existing-spec.yaml")
        existingSpec.writeText(minimalOpenApiSpec())

        val configFile = tempDir.resolve("specmatic.yaml")
        configFile.writeText(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${tempDir.canonicalPath}
                      specs:
                        - existing-spec.yaml
                        - missing-spec.yaml
            """.trimIndent()
        )

        withLocalServer { baseUrl ->
            SpecmaticJUnitSupport.settingsStaging.set(
                ContractTestSettings(
                    configFile = configFile.canonicalPath,
                    testBaseURL = baseUrl
                )
            )

            val (output, tests) = captureStdOut {
                SpecmaticJUnitSupport().contractTest().toList()
            }

            assertThat(output).contains("WARNING: Skipping spec file missing-spec.yaml as it does not exist.")
            assertThat(tests).isNotEmpty
        }
    }

    private fun minimalOpenApiSpec(): String {
        return """
            openapi: 3.0.0
            info:
              title: Existing API
              version: 1.0.0
            paths:
              /hello:
                get:
                  responses:
                    '200':
                      description: OK
        """.trimIndent()
    }

    private fun withLocalServer(block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()

        try {
            val baseUrl = "http://localhost:${server.address.port}"
            block(baseUrl)
        } finally {
            server.stop(0)
        }
    }

    private fun <T> captureStdOut(fn: () -> T): Pair<String, T> {
        val originalOut = System.out
        val stream = ByteArrayOutputStream()
        System.setOut(PrintStream(stream))
        return try {
            val result = fn()
            Pair(stream.toString().trim(), result)
        } finally {
            System.out.flush()
            System.setOut(originalOut)
        }
    }
}
