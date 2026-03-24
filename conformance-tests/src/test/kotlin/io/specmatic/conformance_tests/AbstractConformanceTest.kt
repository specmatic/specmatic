package io.specmatic.conformance_tests

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
    private lateinit var httpExchanges: List<HttpExchange>

    private val spec: OpenApiSpec =
        OpenApiSpec(File("${workDir.absolutePath}/${specsDirName}/$openAPISpecFile"))


    @BeforeAll
    fun setup() {
        dockerCompose = DockerCompose(
            specmaticVersion = System.getProperty("specmatic.version") ?: "latest",
            mitmProxyVersion = "12.2.1",
            pathToOpenAPISpecFile = openAPISpecFile,
            workDir = workDir,
            specsDirName = specsDirName
        )
        loopTestsResult = dockerCompose.runLoopTests()
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
            .withFailMessage { dockerCompose.mustGetAllLogs() }
            .isTrue
    }

    @Test
    @Order(2)
    fun `should only exercise all operations in the openAPI spec and not make any additional non-compliant requests`() {
        val specOps = spec.operations

        logger.info("Operations defined in spec (${specOps.size}):")
        specOps.forEach { logger.info("  $it") }

        logger.info("HTTP exchanges captured (${httpExchanges.size}):")
        httpExchanges.forEach { logger.info("  ${it.method} ${it.path} -> ${it.statusCode} (requestContentType=${it.requestContentType})") }

        val exchangeOps = httpExchanges.map { it.toOperation(spec) }.toSet()

        logger.info("HTTP exchanges mapped to operations (${httpExchanges.size} exchanges -> ${exchangeOps.size} unique operations):")
        exchangeOps.forEach { logger.info("  $it") }

        assertThat(specOps).isEqualTo(exchangeOps)
    }

    @Test
    @Order(4)
    fun `should send valid request bodies`() {
        val errors = httpExchanges.flatMap {
            spec.validateRequestBody(
                body = it.requestBody,
                operation = it.toOperation(spec)
            )
        }

        assertThat(errors)
            .withFailMessage {
                "error=$errors requests=${httpExchanges.joinToString("\n") { it.requestBody }}"
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
                "errors=$errors responses=${httpExchanges.joinToString("\n") { it.responseBody }}"
            }
            .isEmpty()
    }
}
