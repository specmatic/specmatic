package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.SpecmaticSpecConfig
import io.specmatic.core.config.resolveFully
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
import io.specmatic.core.config.wrap
import io.specmatic.core.config.wrapFully
import io.specmatic.reporter.model.SpecType

interface IRunOptions {
    val specs: TemplateOrValue<List<TemplateOrValue<IRunOptionSpecification>>>?
    val config: Map<String, TemplateOrValue<Any>>

    @JsonIgnore
    fun getBaseUrlIfExists(): String?

    @JsonIgnore
    fun getMatchingSpecification(id: String): IRunOptionSpecification? {
        return specs?.resolveFully()?.firstOrNull { it.getId() == id }
    }

    @JsonIgnore
    fun getSpecs(): List<IRunOptionSpecification>? {
        return specs?.resolveFully()
    }

    fun toSpecmaticSpecConfig(specId: String?): SpecmaticSpecConfig {
        val matching = if (specId != null) getMatchingSpecification(specId) else null
        return SpecmaticSpecConfig(getBaseUrlIfExists(), matching, config)
    }

    fun extractBaseUrlFromMap(map: Map<*, *>, defaultHost: String): String? {
        val host = map["host"]?.resolveIfTemplateOrValue()?.toString() ?: defaultHost
        val port = map["port"]?.resolveIfTemplateOrValue()?.toString()?.toIntOrNull() ?: return null
        return "$host:$port"
    }

    private fun Any.resolveIfTemplateOrValue(): Any {
        return when (this) {
            is TemplateOrValue<*> -> this.resolve()
            else -> this
        }
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
            wsdl = wsdl?.resolve()?.copy(specs = wsdl.resolve().specs?.resolveFully()?.filterOverrides()?.wrapFully())?.let(::wrap),
            openapi = openapi?.resolve()?.copy(specs = openapi.resolve().specs?.resolveFully()?.filterOverrides()?.wrapFully())?.let(::wrap),
            protobuf = protobuf?.resolve()?.copy(specs = protobuf.resolve().specs?.resolveFully()?.filterOverrides()?.wrapFully())?.let(::wrap),
            asyncapi = asyncapi?.resolve()?.copy(specs = asyncapi.resolve().specs?.resolveFully()?.filterOverrides()?.wrapFully())?.let(::wrap),
            graphqlsdl = graphqlsdl?.resolve()?.copy(specs = graphqlsdl.resolve().specs?.resolveFully()?.filterOverrides()?.wrapFully())?.let(::wrap),
        )
    }

    @JsonIgnore
    private fun collectOverriddenSpecIds(): Set<String> {
        return buildSet {
            addAll(wsdl?.resolve()?.specs?.resolveFully()?.collectSpecIds().orEmpty())
            addAll(openapi?.resolve()?.specs?.resolveFully()?.collectSpecIds().orEmpty())
            addAll(asyncapi?.resolve()?.specs?.resolveFully()?.collectSpecIds().orEmpty())
            addAll(protobuf?.resolve()?.specs?.resolveFully()?.collectSpecIds().orEmpty())
            addAll(graphqlsdl?.resolve()?.specs?.resolveFully()?.collectSpecIds().orEmpty())
        }
    }

    @JsonIgnore
    fun getRunOptionsFor(specType: SpecType): IRunOptions? {
        return when(specType) {
            SpecType.WSDL -> wsdl?.resolve()
            SpecType.OPENAPI -> openapi?.resolve()
            SpecType.ASYNCAPI -> asyncapi?.resolve()
            SpecType.PROTOBUF -> protobuf?.resolve()
            SpecType.GRAPHQL -> graphqlsdl?.resolve()
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
            wsdl = wsdl?.resolve()?.let { it.copy(specs = it.specs?.resolveFully()?.filterOverrides()?.wrapFully()) }?.let(::wrap),
            openapi = openapi?.resolve()?.let { it.copy(specs = it.specs?.resolveFully()?.filterOverrides()?.wrapFully()) }?.let(::wrap),
            protobuf = protobuf?.resolve()?.let { it.copy(specs = it.specs?.resolveFully()?.filterOverrides()?.wrapFully()) }?.let(::wrap),
            asyncapi = asyncapi?.resolve()?.let { it.copy(specs = it.specs?.resolveFully()?.filterOverrides()?.wrapFully()) }?.let(::wrap),
            graphqlsdl = graphqlsdl?.resolve()?.let { it.copy(specs = it.specs?.resolveFully()?.filterOverrides()?.wrapFully()) }?.let(::wrap),
        )
    }

    @JsonIgnore
    private fun collectOverriddenSpecIds(): Set<String> {
        return buildSet {
            addAll(wsdl?.resolve()?.specs?.resolveFully()?.collectSpecIds().orEmpty())
            addAll(openapi?.resolve()?.specs?.resolveFully()?.collectSpecIds().orEmpty())
            addAll(asyncapi?.resolve()?.specs?.resolveFully()?.collectSpecIds().orEmpty())
            addAll(protobuf?.resolve()?.specs?.resolveFully()?.collectSpecIds().orEmpty())
            addAll(graphqlsdl?.resolve()?.specs?.resolveFully()?.collectSpecIds().orEmpty())
        }
    }

    @JsonIgnore
    fun getRunOptionsFor(specType: SpecType): IRunOptions? {
        return when(specType) {
            SpecType.WSDL -> wsdl?.resolve()
            SpecType.OPENAPI -> openapi?.resolve()
            SpecType.ASYNCAPI -> asyncapi?.resolve()
            SpecType.PROTOBUF -> protobuf?.resolve()
            SpecType.GRAPHQL -> graphqlsdl?.resolve()
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
