package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.SpecmaticSpecConfig
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.reporter.model.SpecType

interface IRunOptions {
    val specs: List<IRunOptionSpecification>?
    val config: Map<String, TemplateOrValue<Any>>

    @JsonIgnore
    fun getBaseUrlIfExists(): String?

    @JsonIgnore
    fun getMatchingSpecification(id: String): IRunOptionSpecification? {
        return specs?.firstOrNull { it.getId() == id }
    }

    fun toSpecmaticSpecConfig(specId: String?): SpecmaticSpecConfig {
        val matching = if (specId != null) getMatchingSpecification(specId) else null
        return SpecmaticSpecConfig(getBaseUrlIfExists(), matching, config)
    }

    fun extractBaseUrlFromMap(map: Map<*, *>, defaultHost: String): String? {
        val host = map["host"]?.toString() ?: defaultHost
        val port = map["port"]?.toString()?.toIntOrNull() ?: return null
        return "$host:$port"
    }
}

data class RunOptions(
    val openapi: TemplateOrValue<OpenApiRunOptions>? = null,
    val wsdl: TemplateOrValue<WsdlRunOptions>? = null,
    val asyncapi: TemplateOrValue<AsyncApiRunOptions>? = null,
    val graphqlsdl: TemplateOrValue<GraphQLSdlRunOptions>? = null,
    val protobuf: TemplateOrValue<ProtobufRunOptions>? = null,
)

data class TestRunOptions(
    val openapi: TemplateOrValue<OpenApiTestConfig>? = null,
    val wsdl: TemplateOrValue<WsdlTestConfig>? = null,
    val asyncapi: TemplateOrValue<AsyncApiTestConfig>? = null,
    val graphqlsdl: TemplateOrValue<GraphQLSdlTestConfig>? = null,
    val protobuf: TemplateOrValue<ProtobufTestConfig>? = null,
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
    val openapi: TemplateOrValue<OpenApiMockConfig>? = null,
    val wsdl: TemplateOrValue<WsdlMockConfig>? = null,
    val asyncapi: TemplateOrValue<AsyncApiMockConfig>? = null,
    val graphqlsdl: TemplateOrValue<GraphQLSdlMockConfig>? = null,
    val protobuf: TemplateOrValue<ProtobufMockConfig>? = null,
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

data class ContextDependentRunOptions(@get:JsonAnyGetter val rawValue: TemplateOrValue<Map<String, TemplateOrValue<Any>>> ) {
    companion object {
        @JvmStatic
        @JsonCreator
        fun create(@JsonAnySetter raw: Map<String, TemplateOrValue<Any>>): ContextDependentRunOptions = ContextDependentRunOptions(TemplateOrValue.Value(raw))
    }
}

private fun <T : IRunOptionSpecification> List<T>?.filterOverrides(): List<T>? {
    return this?.filterNot(IRunOptionSpecification::isNoOpOverride)?.takeUnless { it.isEmpty() }
}

private fun <T : IRunOptionSpecification> List<T>?.collectSpecIds(): Set<String> {
    return this.orEmpty().mapNotNull(IRunOptionSpecification::getId).toSet()
}
