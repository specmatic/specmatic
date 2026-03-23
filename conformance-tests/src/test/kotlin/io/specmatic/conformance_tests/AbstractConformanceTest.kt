package io.specmatic.conformance_tests

import io.specmatic.conformance_test_support.DockerCompose
import io.specmatic.conformance_test_support.HttpExchange
import io.specmatic.conformance_test_support.OpenApiSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
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
    fun `should exercise all operations in the openAPI spec`() {
        val specOps = spec.operations()
        val exchangeOps = httpExchanges.mapNotNull { spec.matchingOperation(it.method, it.path) }.toSet()
        val unexercisedOps = specOps - exchangeOps

        assertThat(unexercisedOps).isEmpty()
    }

    @Test
    @Order(3)
    fun `should not perform operations that aren't in the openAPI spec`() {
        val unspecifiedOperations = httpExchanges.filterNot {
            spec.isMatchingOperation(it.method, it.path)
        }
        assertThat(unspecifiedOperations).isEmpty()
    }

    @Test
    @Order(4)
    fun `should send valid request bodies`() {
        val httpExchangesWithRequestBodies = httpExchanges.filter {
            it.requestBody.isNotBlank()
        }

        val errors = httpExchangesWithRequestBodies.flatMap {
            spec.validateRequestBody(
                body = it.requestBody,
                path = spec.matchingOperation(it.method, it.path)!!.path,
                method = it.method,
                contentType = it.requestContentType()!!
            ).map { error -> error.message }
        }

        assertThat(errors)
            .withFailMessage {
                "error=$errors requests=${httpExchangesWithRequestBodies.joinToString("\n") { it.requestBody }}"
            }
            .isEmpty()
    }

    @Test
    @Order(5)
    fun `should return valid response bodies`() {
        val httpExchangesWithResponseBodies = httpExchanges.filter {
            it.responseBody.isNotBlank()
        }

        val errors = httpExchangesWithResponseBodies.flatMap {
            spec.validateResponseBody(
                body = it.responseBody,
                path = spec.matchingOperation(it.method, it.path)!!.path,
                method = it.method,
                statusCode = it.statusCode,
                contentType = it.responseContentType()!!
            ).map { error -> error.message }
        }

        assertThat(errors)
            .withFailMessage {
                "errors=$errors responses=${httpExchangesWithResponseBodies.joinToString("\n") { it.responseBody }}"
            }
            .isEmpty()
    }
}
