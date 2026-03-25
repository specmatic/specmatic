package io.specmatic.conformance_tests

import io.specmatic.conformance.tests.VersionInfo
import io.specmatic.conformance_test_support.DockerCompose
import io.specmatic.conformance_test_support.HttpExchange
import io.specmatic.conformance_test_support.OpenApiSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.slf4j.LoggerFactory
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
abstract class AbstractConformanceTest(
    private val openAPISpecFile: String,
    private val workDir: File = File("build/resources/test"),
    private val specsDirName: String = "specs"
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private lateinit var dockerCompose: DockerCompose
    private lateinit var loopTestsResult: DockerCompose.CommandResult
    private lateinit var allLogs: String
    private lateinit var httpExchanges: List<HttpExchange>

    private val spec: OpenApiSpec =
        OpenApiSpec(File("${workDir.absolutePath}/${specsDirName}/$openAPISpecFile"))


    @BeforeAll
    fun setup() {
        dockerCompose = DockerCompose(
            specmaticVersion = VersionInfo.version,
            mitmProxyVersion = "12.2.1",
            pathToOpenAPISpecFile = openAPISpecFile,
            workDir = workDir,
            specsDirName = specsDirName
        )
        loopTestsResult = dockerCompose.runLoopTests()
        allLogs = dockerCompose.mustGetAllLogs()
        httpExchanges =
            HttpExchange.parseAll(dockerCompose.mustGetHttpTrafficLogs())
                .filterNot(HttpExchange::isInfraRequest)
    }

    @AfterAll
    fun tearDown() {
        dockerCompose.stopAsync()
    }

    @Test
    @Order(1)
    fun `loop tests should succeed`() {
        assertThat(loopTestsResult.isSuccessful())
            .withFailMessage { allLogs }
            .isTrue
    }

    @Test
    @Order(2)
    fun `should only exercise all operations in the openAPI spec and not make any additional non-compliant requests`() {
        val specOps = spec.operations
        val debugInfoBuilder = StringBuilder()
        debugInfoBuilder.appendLine("Operations defined in spec (${specOps.size}): $specOps")
        debugInfoBuilder.appendLine("HTTP exchanges captured (${httpExchanges.size}): ${httpExchanges.joinToString("\n") { it.toDebugInfo() }}")

        val exchangeOps = httpExchanges.map { it.toOperation(spec) }.toSet()
        debugInfoBuilder.appendLine("HTTP exchanges mapped to operations (${httpExchanges.size} exchanges -> ${exchangeOps.size} unique operations): $exchangeOps")

        assertThat(specOps)
            .withFailMessage {
                debugInfoBuilder
                    .appendLine("specOps - exchangeOps: ${specOps - exchangeOps}")
                    .appendLine()
                    .appendLine(allLogs)
                    .toString()
            }
            .isEqualTo(exchangeOps)
    }

    @Test
    @Order(4)
    fun `should send valid request bodies`() {
        val errors = httpExchanges
            // requests in the test that produce 4xx, 5xx may contain invalid request bodies by design
            .filter(HttpExchange::isSuccessful)
            .flatMap {
            spec.validateRequestBody(
                body = it.requestBody,
                operation = it.toOperation(spec)
            )
        }

        assertThat(errors)
            .withFailMessage {
                "error=$errors requests=${httpExchanges.joinToString("\n") { it.requestBody }}\nallLogs=$allLogs"
            }
            .isEmpty()
    }

    @Test
    @Order(5)
    fun `should return valid response bodies`() {
        val errors = httpExchanges.flatMap {
            spec.validateResponseBody(
                body = it.responseBody,
                operation = it.toOperation(spec),
                responseContentType = it.responseContentType
            )
        }

        assertThat(errors)
            .withFailMessage {
                "errors=$errors responses=${httpExchanges.joinToString("\n") { it.responseBody }}\nallLogs=$allLogs"
            }
            .isEmpty()
    }
}
