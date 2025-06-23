package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.TestConfig
import io.specmatic.test.SpecmaticJUnitSupport.Companion.HOST
import io.specmatic.test.SpecmaticJUnitSupport.Companion.PORT
import io.specmatic.test.SpecmaticJUnitSupport.Companion.PROTOCOL
import io.specmatic.test.SpecmaticJUnitSupport.Companion.TEST_BASE_URL
import io.specmatic.test.listeners.ContractExecutionListener
import io.specmatic.test.reports.coverage.Endpoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.platform.launcher.TestExecutionListener
import org.opentest4j.TestAbortedException
import java.util.*

class SpecmaticJunitSupportTest {
    companion object {
        val initialPropertyKeys = System.getProperties().mapKeys { it.key.toString() }.keys
    }

    @Test
    fun `should retain open api path parameter convention for parameterized endpoints`() {
        val result: Pair<Sequence<ContractTest>, List<Endpoint>> = SpecmaticJUnitSupport().loadTestScenarios(
            "./src/test/resources/spec_with_parameterized_paths.yaml",
            "",
            "",
            TestConfig(emptyMap(), emptyMap()),
            filterName = null,
            filterNotName = null
        )
        val specEndpoints = result.second
        assertThat(specEndpoints.count()).isEqualTo(2)
        assertThat(specEndpoints.all { it.path == "/sayHello/{name}" })
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://test.com", "https://my-json-server.typicode.com/specmatic/specmatic-documentation"])
    fun `should pick up and use valid testBaseURL system property when set`(validURL: String) {
        System.setProperty(TEST_BASE_URL, validURL)
        lateinit var url: String
        assertThatCode {
            url = SpecmaticJUnitSupport().constructTestBaseURL()
        }.doesNotThrowAnyException()
        assertThat(url).isEqualTo(validURL)
    }

    @Test
    fun `should pick up host and port when testBaseURL system property is not set`() {
        val domain = "test.com"
        val port = "8080"
        System.setProperty(HOST, domain)
        System.setProperty(PORT, port)
        lateinit var url: String
        assertThatCode {
            url = SpecmaticJUnitSupport().constructTestBaseURL()
        }.doesNotThrowAnyException()
        assertThat(url).isEqualTo("http://$domain:$port")
    }

    @Test
    fun `should take the domain from the host system property when it is an URI`() {
        val domainName = "test.com"
        val domain = "http://$domainName"
        val port = "8080"
        System.setProperty(HOST, domain)
        System.setProperty(PORT, port)
        lateinit var url: String
        assertThatCode {
            url = SpecmaticJUnitSupport().constructTestBaseURL()
        }.doesNotThrowAnyException()
        assertThat(url).isEqualTo("http://$domainName:$port")
    }

    @Test
    fun `should pick use protocol when system property is set`() {
        val protocol = "https"
        val domain = "test.com"
        val port = "8080"
        System.setProperty(HOST, domain)
        System.setProperty(PORT, port)
        System.setProperty(PROTOCOL, protocol)
        lateinit var url: String
        assertThatCode {
            url = SpecmaticJUnitSupport().constructTestBaseURL()
        }.doesNotThrowAnyException()
        assertThat(url).isEqualTo("$protocol://$domain:$port")
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://invalid url.com", "http://localhost:abcd/", "http://localhost:80 80/", "http://localhost:a123/test"])
    fun `testBaseURL system property should be valid URI`(invalidURL: String) {
        System.setProperty(TEST_BASE_URL, invalidURL)
        val ex = assertThrows<TestAbortedException> {
            SpecmaticJUnitSupport().constructTestBaseURL()
        }
        assertThat(ex.message).isEqualTo("Please specify a valid URL in 'scheme://host[:port][path]' format in testBaseURL environment variable")
    }

    @Test
    fun `host system property should be valid`() {
        System.setProperty(PROTOCOL, "https")
        System.setProperty(HOST, "invalid domain")
        System.setProperty(PORT, "8080")
        val ex = assertThrows<TestAbortedException> {
            SpecmaticJUnitSupport().constructTestBaseURL()
        }
        assertThat(ex.message).isEqualTo("Please specify a valid $PROTOCOL, $HOST and $PORT environment variables")
    }

    @Test
    fun `protocol system property should be valid`() {
        System.setProperty(PROTOCOL, "invalid")
        System.setProperty(HOST, "test.com")
        System.setProperty(PORT, "8080")
        val ex = assertThrows<TestAbortedException> {
            SpecmaticJUnitSupport().constructTestBaseURL()
        }
        assertThat(ex.message).isEqualTo("Please specify a valid $PROTOCOL, $HOST and $PORT environment variables")
    }

    @Test
    fun `port system property should be valid`() {
        System.setProperty(PROTOCOL, "https")
        System.setProperty(HOST, "test.com")
        System.setProperty(PORT, "invalid_port")
        val ex = assertThrows<TestAbortedException> {
            SpecmaticJUnitSupport().constructTestBaseURL()
        }
        assertThat(ex.message).isEqualTo("Please specify a number value for $PORT environment variable")
    }

    @Test
    fun `testBaseURL or host and port system property are mandatory`() {
        val ex = assertThrows<TestAbortedException> {
            SpecmaticJUnitSupport().constructTestBaseURL()
        }
        assertThat(ex.message).isEqualTo("Please specify $TEST_BASE_URL OR host and port as environment variables")
    }

    @Test
    fun `ContractExecutionListener should be registered`() {
        val registeredListeners = ServiceLoader.load(TestExecutionListener::class.java)
            .map { it.javaClass.name }
            .toMutableList()

        assertThat(registeredListeners).contains(ContractExecutionListener::class.java.name)
    }

    @Test
    fun `should be able to get actuator endpoints from swaggerUI`() {
        SpecmaticJUnitSupport.actuatorFromSwagger("", object: TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse(
                    200,
                    body = """
                    openapi: 3.0.1
                    info:
                      title: Order BFF
                      version: '1.0'
                    paths:
                      /orders:
                        post:
                          responses:
                            '200':
                              description: OK
                      /products:
                        post:
                          responses:
                            '200':
                              description: OK
                      /findAvailableProducts/{date_time}:
                        get:
                          parameters:
                            - ${"$"}ref: '#/components/parameters/DateTimeParameter'
                          responses:
                            '200':
                              description: OK
                    components:
                        schemas:
                            DateTime:
                                type: string
                                format: date-time
                        parameters:
                            DateTimeParameter:
                                name: date_time
                                in: path
                                required: true
                                schema:
                                    ${"$"}ref: '#/components/schemas/DateTime'
                    """.trimIndent()
                )
            }
        })

        assertThat(SpecmaticJUnitSupport.openApiCoverageReportInput.endpointsAPISet).isTrue()
        assertThat(SpecmaticJUnitSupport.openApiCoverageReportInput.getApplicationAPIs()).isEqualTo(listOf(
            API("POST", "/orders"),
            API("POST", "/products"),
            API("GET", "/findAvailableProducts/{date_time}")
        ))
    }

    @AfterEach
    fun tearDown() {
        System.getProperties().keys.minus(initialPropertyKeys).forEach { println("Clearing $it"); System.clearProperty(it.toString()) }
    }
}
