package io.specmatic.conformance_tests

import io.specmatic.conformance_test_support.DockerCompose
import io.specmatic.conformance_test_support.HttpExchange
import io.specmatic.conformance_test_support.Operation
import io.specmatic.conformance_test_support.toOperations
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.core.models.ParseOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
abstract class AbstractConformanceTest(
    private val openAPISpecFile: String,
    private val workDir: File = File("build/resources/test"),
    private val specsDirName: String = "specs"
) {
    private lateinit var dockerCompose: DockerCompose
    private lateinit var loopTestsResult: DockerCompose.CommandResult
    private lateinit var httpExchanges: List<HttpExchange>

    private val openApiSpec: OpenAPI by lazy {
        val options = ParseOptions().apply {
            isResolve = true
            isResolveFully = true
        }
        val pathToOpenApiSpecFile = "${workDir.absolutePath}/${specsDirName}/$openAPISpecFile"
        val result = OpenAPIParser().readLocation(pathToOpenApiSpecFile, null, options)
        val api = result.openAPI ?: error("Failed to parse OpenAPI spec at $pathToOpenApiSpecFile: ${result.messages}")
        api
    }


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
        httpExchanges = HttpExchange.parseAll(dockerCompose.mustGetHttpTrafficLogs())
    }

    @AfterAll
    fun tearDown() {
        dockerCompose.stopAsync()
    }

    @Test
    @Order(1)
    fun `loop tests should succeed`() {
        assertThat(loopTestsResult.isSuccessful())
            .withFailMessage { loopTestsResult.output }.isTrue
    }

    @Test
    @Order(2)
    fun `should exercise all operations in the openAPI spec`() {
        val specOps = openApiSpec.toOperations()
        val exchangeOps = httpExchanges.toOperations(specOps)
        val unexercisedOps = specOps - exchangeOps

        assertThat(unexercisedOps).isEmpty()
    }

    @Test
    fun `should not perform operations that aren't in the openAPI spec`() {
        val specOps = openApiSpec.toOperations()
        val exchangeOps = httpExchanges.toOperations(specOps)
        val expectedOps = setOf(
            Operation("HEAD", ""),
            Operation("GET", "/swagger/v1/swagger.yaml"),
        )
        val unSpecifiedOps = exchangeOps - specOps - expectedOps

        assertThat(unSpecifiedOps).isEmpty()
    }
}
