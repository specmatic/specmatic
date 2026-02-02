package io.specmatic.core.config.v3.components.runOptions

import io.specmatic.core.config.v3.components.runOptions.openapi.OpenApiMockRunOptions
import io.specmatic.core.config.v3.components.runOptions.openapi.OpenApiRunOptions
import io.specmatic.core.config.v3.components.runOptions.openapi.OpenApiTestRunOptions

data class RunOptions(
    val openapi: OpenApiRunOptions? = null,
    val asyncapi: AsyncApiRunOptions? = null,
    val graphqlsdl: GraphQLSdlRunOptions? = null,
    val protobuf: ProtobufRunOptions? = null,
)

data class TestRunOptions(
    val openapi: OpenApiTestRunOptions? = null,
    val asyncapi: AsyncApiTestConfig? = null,
    val graphqlsdl: GraphQLSdlTestConfig? = null,
    val protobuf: ProtobufTestConfig? = null,
)

data class MockRunOptions(
    val openapi: OpenApiMockRunOptions? = null,
    val asyncapi: AsyncApiMockConfig? = null,
    val graphqlsdl: GraphQLSdlMockConfig? = null,
    val protobuf: ProtobufMockConfig? = null,
)
