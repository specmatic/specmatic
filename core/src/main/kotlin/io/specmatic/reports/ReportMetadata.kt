package io.specmatic.reports

import io.specmatic.core.SourceProvider
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
enum class ServiceType {
    @SerialName("HTTP")
    HTTP,

    @SerialName("ASYNCAPI")
    ASYNCAPI,

    @SerialName("GRAPHQL")
    GRAPHQL,

    @SerialName("GRPC")
    GRPC, ;

    companion object {
        fun fromString(value: String): ServiceType = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: throw IllegalArgumentException("Invalid ServiceType value '$value'")
    }
}

@Serializable
data class ReportMetadata(
    @SerialName("type") val sourceProvider: SourceProvider? = null,
    @SerialName("repository") val sourceRepository: String? = null,
    @SerialName("branch") val sourceRepositoryBranch: String? = null,
    val specification: String? = null,
    val serviceType: ServiceType? = null,
)

@Serializable(with = ReportItem.Companion.ReportItemSerializer::class)
data class ReportItem(
    val metadata: ReportMetadata,
    val operations: List<OperationGroup>,
) {
    fun merge(other: ReportItem): ReportItem {
        require(metadata == other.metadata) { "Cannot merge ReportItems with different metadata" }
        val groupedMergedOperations = this.operations.plus(other.operations).groupBy { it.hashCodeByIdentity() }
        val reducedOperations = groupedMergedOperations.values.map { ops -> ops.reduce(OperationGroup::merge) }
        return ReportItem(metadata, reducedOperations)
    }

    fun equalsByIdentity(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is ReportItem || other::class != this::class) return false
        return this.metadata == other.metadata
    }

    fun hashCodeByIdentity(): Int = listOf(metadata).hashCode()

    companion object {
        object ReportItemSerializer : KSerializer<ReportItem> {
            private val metadataSer = ReportMetadata.serializer()
            private val operationsSer = ListSerializer(OperationGroup.serializer())

            override val descriptor: SerialDescriptor = buildClassSerialDescriptor("StubUsageReportItem(flat)") {
                element<SourceProvider?>("type")
                element<String?>("repository")
                element<String?>("branch")
                element<String?>("specification")
                element<ServiceType?>("serviceType")
                element("operations", ListSerializer(OperationGroup.serializer()).descriptor)
            }

            override fun serialize(encoder: Encoder, value: ReportItem) {
                require(encoder is JsonEncoder) { "Only JSON Encoder is supported" }
                val metaObj = encoder.json.encodeToJsonElement(metadataSer, value.metadata).jsonObject
                val opsEl = encoder.json.encodeToJsonElement(operationsSer, value.operations)
                encoder.encodeJsonElement(
                    buildJsonObject {
                        metaObj.forEach(::put)
                        put("operations", opsEl)
                    },
                )
            }

            override fun deserialize(decoder: Decoder): ReportItem {
                require(decoder is JsonDecoder) { "Only JSON Decoder is supported" }
                val flat = decoder.decodeJsonElement().jsonObject
                val metaObj = buildJsonObject { flat.filter { it.key != "operations" }.forEach(::put) }
                val metadata = decoder.json.decodeFromJsonElement(metadataSer, metaObj)
                val opsEl = flat["operations"] ?: error("Expected key `operations` is missing")
                val operations = decoder.json.decodeFromJsonElement(operationsSer, opsEl)
                return ReportItem(metadata, operations)
            }
        }
    }
}
