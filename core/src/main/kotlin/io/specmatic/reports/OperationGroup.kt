package io.specmatic.reports

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

@Serializable(with = OperationGroup.Companion.OperationGroupSuperClassSerializer::class)
sealed interface OperationGroup {
    @Transient val identityFields: List<Any?>
    val count: Int?
    val coverageStatus: String?

    fun merge(other: OperationGroup): OperationGroup

    fun withCount(newCount: Int?): OperationGroup

    fun withCoverageStatus(coverageStatus: String?): OperationGroup

    fun equalsByIdentity(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is OperationGroup || other::class != this::class) return false
        return identityFields == other.identityFields
    }

    fun hashCodeByIdentity(): Int = identityFields.hashCode()

    companion object {
        object OperationGroupSuperClassSerializer : JsonContentPolymorphicSerializer<OperationGroup>(OperationGroup::class) {
            override fun selectDeserializer(element: JsonElement): DeserializationStrategy<OperationGroup> {
                return when {
                    "path" in element.jsonObject -> OpenApiOperationGroup.serializer()
                    "channel" in element.jsonObject -> AsyncApiOperationGroup.serializer()
                    "field" in element.jsonObject -> GraphQLOperationGroup.serializer()
                    else -> GrpcOperationGroup.serializer()
                }
            }
        }
    }
}

// OPENAPI
@Serializable
@SerialName("HTTP")
data class OpenApiOperationGroup(
    val path: String,
    val method: String,
    val responseCode: Int,
    override val count: Int? = null,
    override val coverageStatus: String? = null,
) : OperationGroup {
    @Transient override val identityFields = listOf(path, method, responseCode)

    override fun withCount(newCount: Int?): OperationGroup = copy(count = newCount)

    override fun withCoverageStatus(coverageStatus: String?): OperationGroup = copy(coverageStatus = coverageStatus)

    override fun merge(other: OperationGroup): OperationGroup {
        require(other is OpenApiOperationGroup) { "Cannot merge OpenApiOperationGroup with ${other::class.simpleName}" }
        require(this.equalsByIdentity(other)) { "Can only merge identical OpenApiOperationGroups" }
        return copy(count = (this.count ?: 0) + (other.count ?: 0))
    }
}

// ASYNC-API
enum class AsyncApiOperationAction {
    @SerialName("send")
    SEND,

    @SerialName("receive")
    RECEIVE, ;

    companion object {
        fun fromString(value: String): AsyncApiOperationAction = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: throw IllegalArgumentException("Invalid AsyncApiOperationAction value '$value'")
    }
}

@Serializable
@SerialName("ASYNCAPI")
data class AsyncApiOperationGroup(
    val operationId: String?,
    val channel: String,
    val replyChannel: String?,
    val action: AsyncApiOperationAction,
    override val count: Int? = null,
    override val coverageStatus: String? = null,
) : OperationGroup {
    @Transient override val identityFields = listOf(operationId, channel, replyChannel, action)

    override fun withCount(newCount: Int?): OperationGroup = copy(count = newCount)

    override fun withCoverageStatus(coverageStatus: String?): OperationGroup = copy(coverageStatus = coverageStatus)

    override fun merge(other: OperationGroup): OperationGroup {
        require(other is AsyncApiOperationGroup) { "Cannot merge AsyncApiOperationGroup with ${other::class.simpleName}" }
        require(this.equalsByIdentity(other)) { "Can only merge identical AsyncApiOperationGroups" }
        return copy(count = (this.count ?: 0) + (other.count ?: 0))
    }
}

// GRAPHQL
enum class GraphQLOperationType {
    @SerialName("query")
    QUERY,

    @SerialName("mutation")
    MUTATION,

    @SerialName("unknown")
    UNKNOWN, ;

    companion object {
        fun fromString(value: String): GraphQLOperationType = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: throw IllegalArgumentException("Invalid GraphQLOperationType value '$value'")
    }
}

@Serializable
@SerialName("GRAPHQL")
data class GraphQLOperationGroup(
    val field: String,
    val type: GraphQLOperationType,
    override val count: Int? = null,
    override val coverageStatus: String? = null,
) : OperationGroup {
    @Transient override val identityFields = listOf(field, type)

    override fun withCount(newCount: Int?): OperationGroup = copy(count = newCount)

    override fun withCoverageStatus(coverageStatus: String?): OperationGroup = copy(coverageStatus = coverageStatus)

    override fun merge(other: OperationGroup): OperationGroup {
        require(other is GraphQLOperationGroup) { "Cannot merge GraphQLOperationGroup with ${other::class.simpleName}" }
        require(this.equalsByIdentity(other)) { "Can only merge identical GraphQLOperationGroups" }
        return copy(count = (this.count ?: 0) + (other.count ?: 0))
    }
}

// GRPC
@Serializable
@SerialName("GRPC")
data class GrpcOperationGroup(
    val service: String,
    val rpc: String,
    override val count: Int? = null,
    override val coverageStatus: String? = null,
) : OperationGroup {
    @Transient override val identityFields = listOf(service, rpc)

    override fun withCount(newCount: Int?): OperationGroup = copy(count = newCount)

    override fun withCoverageStatus(coverageStatus: String?): OperationGroup = copy(coverageStatus = coverageStatus)

    override fun merge(other: OperationGroup): OperationGroup {
        require(other is GrpcOperationGroup) { "Cannot merge GrpcOperationGroup with ${other::class.simpleName}" }
        require(this.equalsByIdentity(other)) { "Can only merge identical GrpcOperationGroups" }
        return copy(count = (this.count ?: 0) + (other.count ?: 0))
    }
}
