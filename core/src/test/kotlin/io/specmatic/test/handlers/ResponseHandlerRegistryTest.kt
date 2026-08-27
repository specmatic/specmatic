package io.specmatic.test.handlers

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Scenario
import io.specmatic.core.ScenarioInfo
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URL
import java.util.Collections

class ResponseHandlerRegistryTest {
    private val scenario = Scenario(
        ScenarioInfo(
            httpResponsePattern = HttpResponsePattern(status = 202),
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI,
        )
    )

    @BeforeEach
    fun resetProvider() {
        ServiceLoadedTestResponseHandlerProvider.reset()
    }

    @Test
    fun `loads provider handlers with the current feature and original scenario`() {
        val feature = Feature(name = ServiceLoadedTestResponseHandlerProvider.TEST_FEATURE_NAME, protocol = SpecmaticProtocol.HTTP)

        ResponseHandlerRegistry(feature, scenario)

        assertThat(ServiceLoadedTestResponseHandlerProvider.receivedFeature).isSameAs(feature)
        assertThat(ServiceLoadedTestResponseHandlerProvider.receivedOriginalScenario).isSameAs(scenario)
    }

    @Test
    fun `provider handlers can handle accepted responses`() {
        val feature = Feature(name = ServiceLoadedTestResponseHandlerProvider.TEST_FEATURE_NAME, protocol = SpecmaticProtocol.HTTP)
        val registry = ResponseHandlerRegistry(feature, scenario)

        val handler = registry.getHandlerFor(
            HttpResponse(status = 202, headers = mapOf("Link" to "</monitor/1>;rel=related;title=monitor")),
            scenario,
        )

        assertThat(handler).isSameAs(ServiceLoadedTestResponseHandlerProvider.createdHandler)
    }

    @Test
    fun `provider handlers take precedence over directly service-loaded legacy handlers`() {
        val feature = Feature(name = ServiceLoadedTestResponseHandlerProvider.TEST_FEATURE_NAME, protocol = SpecmaticProtocol.HTTP)
        val registry = ResponseHandlerRegistry(feature, scenario)

        val handler = registry.getHandlerFor(HttpResponse(status = ServiceLoadedTestResponseHandler.STATUS), scenario)

        assertThat(handler).isSameAs(ServiceLoadedTestResponseHandlerProvider.createdHandler)
    }

    @Test
    fun `directly service-loaded legacy handlers remain available`() {
        val feature = Feature(name = "legacy-handler-test", protocol = SpecmaticProtocol.HTTP)
        val registry = ResponseHandlerRegistry(feature, scenario)

        val handler = registry.getHandlerFor(HttpResponse(status = ServiceLoadedTestResponseHandler.STATUS), scenario)

        assertThat(handler).isInstanceOf(ServiceLoadedTestResponseHandler::class.java)
    }

    @Test
    fun `accepted and too-many-requests responses have no special handler when no provider is installed`() {
        val feature = Feature(name = "built-in-handler-test", protocol = SpecmaticProtocol.HTTP)
        withoutResponseHandlerProviders {
            val registry = ResponseHandlerRegistry(feature, scenario)
            val acceptedHandler = registry.getHandlerFor(
                HttpResponse(status = 202, headers = mapOf("Link" to "</monitor/1>;rel=related;title=monitor")),
                scenario,
            )
            val tooManyRequestsHandler = registry.getHandlerFor(HttpResponse(status = 429), scenario)

            assertThat(acceptedHandler).isNull()
            assertThat(tooManyRequestsHandler).isNull()
        }
        assertThat(ServiceLoadedTestResponseHandlerProvider.receivedFeature).isNull()
    }

    private fun <T> withoutResponseHandlerProviders(block: () -> T): T {
        val previousClassLoader = Thread.currentThread().contextClassLoader
        val providerServiceName = "META-INF/services/${ResponseHandlerProvider::class.java.name}"
        Thread.currentThread().contextClassLoader = object : ClassLoader(previousClassLoader) {
            override fun getResources(name: String): java.util.Enumeration<URL> {
                return if (name == providerServiceName) Collections.emptyEnumeration() else super.getResources(name)
            }
        }

        return try {
            block()
        } finally {
            Thread.currentThread().contextClassLoader = previousClassLoader
        }
    }
}

class ServiceLoadedTestResponseHandlerProvider : ResponseHandlerProvider {
    override fun handlersFor(feature: Feature, originalScenario: Scenario): List<ResponseHandler> {
        if (feature.name != TEST_FEATURE_NAME) return emptyList()

        receivedFeature = feature
        receivedOriginalScenario = originalScenario
        return listOf(createdHandler)
    }

    companion object {
        const val TEST_FEATURE_NAME = "provider-handler-test"
        val createdHandler = TestProviderResponseHandler()
        var receivedFeature: Feature? = null
            private set
        var receivedOriginalScenario: Scenario? = null
            private set

        fun reset() {
            receivedFeature = null
            receivedOriginalScenario = null
        }
    }
}

class TestProviderResponseHandler : ResponseHandler {
    override fun canHandle(response: HttpResponse, scenario: Scenario): Boolean {
        return response.status == 202 || response.status == ServiceLoadedTestResponseHandler.STATUS
    }

    override fun handle(
        request: HttpRequest,
        response: HttpResponse,
        testScenario: Scenario,
        testExecutor: TestExecutor,
    ): ResponseHandlingResult = ResponseHandlingResult.Continue(response)
}

class ServiceLoadedTestResponseHandler : ResponseHandler {
    override fun canHandle(response: HttpResponse, scenario: Scenario): Boolean = response.status == STATUS

    override fun handle(
        request: HttpRequest,
        response: HttpResponse,
        testScenario: Scenario,
        testExecutor: TestExecutor,
    ): ResponseHandlingResult = ResponseHandlingResult.Continue(response)

    companion object {
        const val STATUS = 799
    }
}
