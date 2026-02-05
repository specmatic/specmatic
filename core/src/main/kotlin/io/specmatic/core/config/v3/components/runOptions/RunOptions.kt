package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.reporter.model.SpecType

interface IRunOptions {
    val baseUrl: String?
    val specs: List<IRunOptionSpecification>?

    @JsonIgnore
    fun getMatchingSpecification(id: String): IRunOptionSpecification? {
        return specs?.firstOrNull { it.getId() == id }
    }
}

data class RunOptions(
    val openapi: OpenApiRunOptions? = null,
    val asyncapi: AsyncApiRunOptions? = null,
    val graphqlsdl: GraphQLSdlRunOptions? = null,
    val protobuf: ProtobufRunOptions? = null,
)

data class TestRunOptions(
    val openapi: OpenApiTestConfig? = null,
    val asyncapi: AsyncApiTestConfig? = null,
    val graphqlsdl: GraphQLSdlTestConfig? = null,
    val protobuf: ProtobufTestConfig? = null,
) {
    @JsonIgnore
    fun getRunOptionsFor(specType: SpecType): IRunOptions? {
        return when(specType) {
            SpecType.OPENAPI -> openapi
            SpecType.WSDL -> openapi
            SpecType.ASYNCAPI -> asyncapi
            SpecType.PROTOBUF -> protobuf
            SpecType.GRAPHQL -> graphqlsdl
        }
    }
}

data class MockRunOptions(
    val openapi: OpenApiMockConfig? = null,
    val asyncapi: AsyncApiMockConfig? = null,
    val graphqlsdl: GraphQLSdlMockConfig? = null,
    val protobuf: ProtobufMockConfig? = null,
) {
    @JsonIgnore
    fun getRunOptionsFor(specType: SpecType): IRunOptions? {
        return when(specType) {
            SpecType.OPENAPI -> openapi
            SpecType.WSDL -> openapi
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
