package io.specmatic.stub.report

import io.specmatic.core.HttpRequest
import io.specmatic.stub.HttpStub
import io.specmatic.stub.createStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket

class OpenApiMockUsageIntegrationTest {
    @Test
    fun `mock ctrf spec configs should preserve git root relative spec paths`(@TempDir tempDir: File) {
        val repoRoot = tempDir.resolve("repo").apply { mkdirs() }
        runGit(repoRoot, "init")

        repoRoot.resolve("specs/orders.yaml").apply {
            parentFile.mkdirs()
            writeText(
                """
                openapi: 3.0.0
                info:
                  title: Orders
                  version: 1.0.0
                paths:
                  /orders:
                    get:
                      responses:
                        '200':
                          description: OK
                """.trimIndent()
            )
        }

        val specsDirectory = repoRoot.resolve("specs").absolutePath
        val configFile =
            repoRoot.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 3
                    dependencies:
                      services:
                        - service:
                            definitions:
                              - definition:
                                  source:
                                    filesystem:
                                      directory: $specsDirectory
                                  specs:
                                    - spec:
                                        id: orders
                                        path: orders.yaml
                    """.trimIndent()
                )
            }

        val stubPort = ServerSocket(0).use { it.localPort }

        (createStub(host = "127.0.0.1", port = stubPort, timeoutMillis = 0L, givenConfigFileName = configFile.canonicalPath) as HttpStub).use { stub ->
            val response = stub.client.execute(HttpRequest(path = "/orders", method = "GET"))
            assertThat(response.status).isEqualTo(200)

            val mockUsage = OpenApiMockUsage()
            val ctrfTestResultRecords = stub.ctrfTestResultRecords()
            assertThat(ctrfTestResultRecords).hasSize(1)
            assertThat(ctrfTestResultRecords.single().specification).isEqualTo("specs/orders.yaml")
            ctrfTestResultRecords.forEach(mockUsage::addTestResultRecord)

            val report = mockUsage.generate()
            assertThat(report.getSpecConfigs()).hasSize(1)
            assertThat(report.getSpecConfigs().single().specification).isEqualTo("specs/orders.yaml")
        }
    }

    private fun runGit(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + args).start()
        val exitCode = process.waitFor()
        assertThat(exitCode).isZero()
    }
}
