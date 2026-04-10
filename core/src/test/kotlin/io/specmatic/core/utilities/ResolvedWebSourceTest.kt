package io.specmatic.core.utilities

import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.specmatic.core.DEFAULT_WORKING_DIRECTORY
import io.specmatic.core.pattern.ContractException
import io.specmatic.toContractSourceEntries
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.ServerSocket

class ResolvedWebSourceTest {
    @Test
    fun `should download specification using base url and relative path`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/specifications/spec.yaml") {
                    call.respondText("data")
                }
            }
        }.start(wait = false)

        try {
            val source = ResolvedWebSource(
                baseUrl = "http://localhost:$port/specifications",
                testContracts = emptyList(),
                stubContracts = listOf("spec.yaml").toContractSourceEntries()
            )

            val contractData = source.loadContracts({ it.stubContracts }, DEFAULT_WORKING_DIRECTORY, "specmatic.yaml")

            assertThat(contractData).hasSize(1)
            assertThat(File(contractData.single().path).readText()).isEqualTo("data")
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `should preserve configured yaml extension when downloading`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/specifications/spec1.yaml") {
                    call.respondText("openapi: 3.0.0")
                }
            }
        }.start(wait = false)

        try {
            val source = ResolvedWebSource(
                baseUrl = "http://localhost:$port/specifications",
                testContracts = listOf("spec1.yaml").toContractSourceEntries(),
                stubContracts = emptyList()
            )

            val contractData = source.loadContracts({ it.testContracts }, DEFAULT_WORKING_DIRECTORY, "specmatic.yaml")

            assertThat(contractData.single().path).endsWith("spec1.yaml")
            assertThat(File(contractData.single().path).readText()).isEqualTo("openapi: 3.0.0")
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `should reject absolute url in web source specification list`() {
        val exception = assertThrows<ContractException> {
            ResolvedWebSource.validateRelativeSpecPath("http://example.com/spec.yaml")
        }

        assertThat(exception.message).contains("Web source specifications must be relative paths")
    }

    @Test
    fun `should reject absolute url in web source specification list after Windows path normalization`() {
        val exception = assertThrows<ContractException> {
            ResolvedWebSource.validateRelativeSpecPath("http:\\\\example.com\\spec.yaml")
        }

        assertThat(exception.message).contains("Web source specifications must be relative paths")
    }

    @AfterEach
    fun tearDown() {
        File(DEFAULT_WORKING_DIRECTORY).deleteRecursively()
        File(".specmatic").deleteRecursively()
    }
}
