package io.specmatic.core.config.v3.components.runOptions

import io.specmatic.core.config.v3.ServerOrigin
import io.specmatic.core.config.v3.spi.ProtocolConfig
import io.specmatic.core.config.v3.spi.ProtocolConfigServerExtractor
import io.specmatic.core.config.v3.spi.TypedProtocolConfigServerExtractor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class RunOptionsSpecificationsTest {
    @Test
    fun `openapi getServerOrigin should return baseUrl when present`() {
        val runOptions = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(baseUrl = "http://example.com"))
        assertEquals(ServerOrigin.from("http://example.com"), runOptions.getServerOrigin("localhost"))
    }

    @Test
    fun `openapi getServerOrigin should return http origin when baseUrl is absent`() {
        val runOptions = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(host = "localhost", port = 8080))
        assertEquals(ServerOrigin.from("http", "localhost", 8080), runOptions.getServerOrigin("localhost"))

        val portOnly = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(port = 9000))
        assertEquals(ServerOrigin.from("http", "0.0.0.0", 9000), portOnly.getServerOrigin("0.0.0.0"))
    }

    @Test
    fun `openapi actuator url should be treated as a spec override`() {
        val runOptions = OpenApiRunOptionsSpecifications(
            OpenApiRunOptionsSpecifications.Value(actuatorUrl = "http://example.com/actuator")
        )

        assertFalse(runOptions.isNoOpOverride())
    }

    @Test
    fun `openapi swagger UI base url should be treated as a spec override`() {
        val runOptions = OpenApiRunOptionsSpecifications(
            OpenApiRunOptionsSpecifications.Value(swaggerUiBaseUrl = "http://example.com/swagger-ui")
        )

        assertFalse(runOptions.isNoOpOverride())
    }

    @Test
    fun `wsdl getServerOrigin should return baseUrl when present`() {
        val runOptions = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(baseUrl = "http://example.com"))
        assertEquals(ServerOrigin.from("http://example.com"), runOptions.getServerOrigin("localhost"))
    }

    @Test
    fun `wsdl getServerOrigin should return http origin when baseUrl is absent`() {
        val runOptions = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(host = "localhost", port = 9000))
        assertEquals(ServerOrigin.from("http", "localhost", 9000), runOptions.getServerOrigin("localhost"))

        val portOnly = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(port = 9000))
        assertEquals(ServerOrigin.from("http", "0.0.0.0", 9000), portOnly.getServerOrigin("0.0.0.0"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protocolRunOptions")
    fun `asyncapi graphql and protobuf run options hand global and spec override config to the protocol extractor`(
        name: String,
        runOptions: IRunOptions,
        expectedType: RunOptionType,
        specId: String,
        specification: IRunOptionSpecification,
    ) {
        val expectedOrigin = ServerOrigin.NetworkAddress(host = "spi.example", port = 8123)
        val extractor = RecordingProtocolConfigServerExtractor(expectedOrigin)
        ProtocolConfigServerExtractor.with(extractor) {
            assertEquals(expectedOrigin, runOptions.getServerOrigin())
            assertEquals(expectedOrigin, runOptions.getServerOrigin(specId))
        }

        assertEquals(2, extractor.configurations.size)
        val global = extractor.configurations[0] as ProtocolConfig.Global<*>
        assertSame(runOptions, global.runOptions)
        assertEquals(expectedType, global.runOptionType)

        val override = extractor.configurations[1] as ProtocolConfig.Override<*>
        assertSame(runOptions, override.runOptions)
        assertSame(specification, override.spec)
        assertEquals(expectedType, override.runOptionType)
    }

    private class RecordingProtocolConfigServerExtractor(
        private val origin: ServerOrigin,
    ) : ProtocolConfigServerExtractor {
        val configurations = mutableListOf<ProtocolConfig<*>>()

        override fun extractNetworkOrigin(configuration: ProtocolConfig<*>): ServerOrigin {
            configurations += configuration
            return origin
        }
    }

    @Nested
    inner class TypedExtractor {
        @Test
        fun `extracts origin from matching typed global configuration`() {
            val expectedOrigin = ServerOrigin.NetworkAddress(host = "async.example", port = 9090)
            val extractor = TypedAsyncTestServerOriginExtractor(expectedOrigin)
            val configuration = ProtocolConfig.Global(AsyncApiTestConfig(), RunOptionType.TEST)

            val testOrigin = extractor.extractNetworkOrigin(configuration)
            assertThat(testOrigin).isEqualTo(expectedOrigin)

            val mockOrigin = extractor.extractNetworkOrigin(configuration.copy(runOptionType = RunOptionType.MOCK))
            assertThat(mockOrigin).isNull()
        }
    }

    companion object {
        @JvmStatic
        fun protocolRunOptions(): Stream<Arguments> = Stream.of(
            handoffArguments("AsyncAPI test", RunOptionType.TEST) { AsyncApiTestConfig(specs = it) },
            handoffArguments("AsyncAPI mock", RunOptionType.MOCK) { AsyncApiMockConfig(specs = it) },
            handoffArguments("GraphQL test", RunOptionType.TEST) { GraphQLSdlTestConfig(specs = it) },
            handoffArguments("GraphQL mock", RunOptionType.MOCK) { GraphQLSdlMockConfig(specs = it) },
            handoffArguments("protobuf test", RunOptionType.TEST) { ProtobufTestConfig(specs = it) },
            handoffArguments("protobuf mock", RunOptionType.MOCK) { ProtobufMockConfig(specs = it) },
        )

        private fun handoffArguments(
            name: String,
            runOptionType: RunOptionType,
            createRunOptions: (List<RunOptionsSpecifications>) -> IRunOptions,
        ): Arguments {
            val specId = "spec-${name.lowercase().replace(' ', '-')}"
            val specification = RunOptionsSpecifications(RunOptionsSpecifications.Value(id = specId, host = "override.example", port = 9090))
            return Arguments.of(
                name,
                createRunOptions(listOf(specification)),
                runOptionType,
                specId,
                specification,
            )
        }
    }

    private class TypedAsyncTestServerOriginExtractor(private val origin: ServerOrigin) :
        TypedProtocolConfigServerExtractor<AsyncApiTestConfig>(AsyncApiTestConfig::class, RunOptionType.TEST)
    {
        override fun extractGlobalNetworkOrigin(configuration: AsyncApiTestConfig): ServerOrigin = origin
    }
}
