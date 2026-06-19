package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.SpecmaticSpecConfig
import io.specmatic.core.config.v3.ServerOrigin
import io.specmatic.reporter.model.SpecType

interface IRunOptions {
    val specs: List<IRunOptionSpecification>?
    val config: Map<String, Any>

    @JsonIgnore
    fun gerServerOrigin(): ServerOrigin?

    @JsonIgnore
    fun getMatchingSpecification(id: String): IRunOptionSpecification? {
        return specs?.firstOrNull { it.getId() == id }
    }

    fun toSpecmaticSpecConfig(specId: String?): SpecmaticSpecConfig {
        val matching = if (specId != null) getMatchingSpecification(specId) else null
        return SpecmaticSpecConfig(gerServerOrigin()?.baseUrl, matching, config)
    }

    fun extractServerOriginFromMap(map: Map<*, *>, defaultHost: String): ServerOrigin? {
        val host = map["host"]?.toString() ?: defaultHost
        val port = map["port"]?.toString()?.toIntOrNull() ?: return null
        return ServerOrigin.from(host = host, port = port)
    }
}

data class RunOptions(
    val openapi: OpenApiRunOptions? = null,
    val wsdl: WsdlRunOptions? = null,
    val asyncapi: AsyncApiRunOptions? = null,
    val graphqlsdl: GraphQLSdlRunOptions? = null,
    val protobuf: ProtobufRunOptions? = null,
)

data class TestRunOptions(
    val openapi: OpenApiTestConfig? = null,
    val wsdl: WsdlTestConfig? = null,
    val asyncapi: AsyncApiTestConfig? = null,
    val graphqlsdl: GraphQLSdlTestConfig? = null,
    val protobuf: ProtobufTestConfig? = null,
) {
    @JsonIgnore
    fun hasSpecOverride(specId: String): Boolean {
        return collectOverriddenSpecIds().contains(specId)
    }

    @JsonIgnore
    fun dropNoOpSpecificationOverrides(): TestRunOptions {
        return copy(
            wsdl = wsdl?.copy(specs = wsdl.specs.filterOverrides()),
            openapi = openapi?.copy(specs = openapi.specs.filterOverrides()),
            protobuf = protobuf?.copy(specs = protobuf.specs.filterOverrides()),
            asyncapi = asyncapi?.copy(specs = asyncapi.specs.filterOverrides()),
            graphqlsdl = graphqlsdl?.copy(specs = graphqlsdl.specs.filterOverrides()),
        )
    }

    @JsonIgnore
    private fun collectOverriddenSpecIds(): Set<String> {
        return buildSet {
            addAll(wsdl?.specs.collectSpecIds())
            addAll(openapi?.specs.collectSpecIds())
            addAll(asyncapi?.specs.collectSpecIds())
            addAll(protobuf?.specs.collectSpecIds())
            addAll(graphqlsdl?.specs.collectSpecIds())
        }
    }

    @JsonIgnore
    fun getRunOptionsFor(specType: SpecType): IRunOptions? {
        return when(specType) {
            SpecType.WSDL -> wsdl
            SpecType.OPENAPI -> openapi
            SpecType.ASYNCAPI -> asyncapi
            SpecType.PROTOBUF -> protobuf
            SpecType.GRAPHQL -> graphqlsdl
        }
    }
}

data class MockRunOptions(
    val openapi: OpenApiMockConfig? = null,
    val wsdl: WsdlMockConfig? = null,
    val asyncapi: AsyncApiMockConfig? = null,
    val graphqlsdl: GraphQLSdlMockConfig? = null,
    val protobuf: ProtobufMockConfig? = null,
) {
    @JsonIgnore
    fun hasSpecOverride(specId: String): Boolean {
        return collectOverriddenSpecIds().contains(specId)
    }

    @JsonIgnore
    fun dropNoOpSpecificationOverrides(): MockRunOptions {
        return copy(
            wsdl = wsdl?.copy(specs = wsdl.specs.filterOverrides()),
            openapi = openapi?.copy(specs = openapi.specs.filterOverrides()),
            protobuf = protobuf?.copy(specs = protobuf.specs.filterOverrides()),
            asyncapi = asyncapi?.copy(specs = asyncapi.specs.filterOverrides()),
            graphqlsdl = graphqlsdl?.copy(specs = graphqlsdl.specs.filterOverrides()),
        )
    }

    @JsonIgnore
    private fun collectOverriddenSpecIds(): Set<String> {
        return buildSet {
            addAll(wsdl?.specs.collectSpecIds())
            addAll(openapi?.specs.collectSpecIds())
            addAll(asyncapi?.specs.collectSpecIds())
            addAll(protobuf?.specs.collectSpecIds())
            addAll(graphqlsdl?.specs.collectSpecIds())
        }
    }

    @JsonIgnore
    fun getRunOptionsFor(specType: SpecType): IRunOptions? {
        return when(specType) {
            SpecType.WSDL -> wsdl
            SpecType.OPENAPI -> openapi
            SpecType.ASYNCAPI -> asyncapi
            SpecType.PROTOBUF -> protobuf
            SpecType.GRAPHQL -> graphqlsdl
        }
    }
}

data class ContextDependentRunOptions(@get:JsonAnyGetter val rawValue: Map<String, Any?>) {
    companion object {
        @JvmStatic
        @JsonCreator
        fun create(@JsonAnySetter raw: Map<String, Any?>): ContextDependentRunOptions = ContextDependentRunOptions(raw)
    }
}

private fun <T : IRunOptionSpecification> List<T>?.filterOverrides(): List<T>? {
    return this?.filterNot(IRunOptionSpecification::isNoOpOverride)?.takeUnless { it.isEmpty() }
}

private fun <T : IRunOptionSpecification> List<T>?.collectSpecIds(): Set<String> {
    return this.orEmpty().mapNotNull(IRunOptionSpecification::getId).toSet()
}
