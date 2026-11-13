package io.specmatic.core.config

import com.fasterxml.jackson.core.type.TypeReference

data class ResourceDetails(
    val name: String? = null,
    val basePath: String? = null,
    val commonOperationProperties: ResourceOperationProperties = ResourceOperationProperties(),
    val operations: List<ResourceOperationDetails> = emptyList()
)

data class ResourceOperationProperties(
    val action: String? = null,
    val identifier: String? = null,
    val idempotencyKey: String? = null
)

data class ResourceOperationDetails(
    val id: String? = null,
    val action: String? = null,
    val identifier: String? = null,
    val idempotencyKey: String? = null
)

private data class ResourceDetailsEntry(val resource: ResourceDetailsConfig)

private data class ResourceDetailsConfig(
    val name: String? = null,
    val basePath: String? = null,
    val commonOperationProperties: ResourceOperationProperties = ResourceOperationProperties(),
    val operations: List<ResourceOperationEntry> = emptyList()
)

private data class ResourceOperationEntry(val operation: ResourceOperationDetails)

internal fun resourceDetailsFrom(config: Map<String, Any>): List<ResourceDetails> {
    val resources = config["resources"] ?: return emptyList()
    val entries: List<ResourceDetailsEntry> = objectMapper.convertValue(
        resources,
        object : TypeReference<List<ResourceDetailsEntry>>() {}
    )
    return entries.map { entry ->
        val resource = entry.resource
        ResourceDetails(
            name = resource.name,
            basePath = resource.basePath,
            commonOperationProperties = resource.commonOperationProperties,
            operations = resource.operations.map { it.operation }
        )
    }
}
