package io.specmatic.core.utilities

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.specmatic.testPort
import io.specmatic.toContractSourceEntries
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI

class WebSourceTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `derives the cache path host from the authority when URI host is null`() {
        // Hosts such as "contract_service" make URI.host null, which previously cached specs under "null/...".
        // The host (without port) must be recovered from the authority so caches don't collide across hosts.
        val webSource = WebSource(emptyList(), emptyList())

        assertThat(webSource.toSpecificationPath(URI.create("http://contract_service/spec.yaml")))
            .isEqualTo("contract_service/spec.yaml")
        assertThat(webSource.toSpecificationPath(URI.create("http://contract_service:8080/spec.yaml")))
            .isEqualTo("contract_service/spec.yaml")
    }

    @Test
    fun `test downloading from a web source`() {
        val port: Int = testPort()

        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/spec.yaml") {
                    call.respondText("data")
                }
            }
        }.start(wait = false)


        try {
            val specificationOnTheWeb = "http://localhost:$port/spec.yaml"

            val webSource = WebSource(
                listOf(),
                listOf(specificationOnTheWeb).toContractSourceEntries()
            )

            val contractData = webSource.loadContracts({ source -> source.stubContracts }, directory.path, "")

            assertThat(contractData).isNotEmpty
            assertThat(contractData).allSatisfy {
                val data = File(it.path).readText()
                assertThat(data).isEqualTo("data")
            }
        } finally {
            server.stop(0, 0)
        }
    }
}
