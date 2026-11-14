package io.specmatic.core.config.statefulmock

import io.specmatic.core.pattern.ContractException

data class ResourceDetails(
    val name: String,
    val basePath: String,
    val commonOperationProperties: ResourceOperationProperties = ResourceOperationProperties(),
    val operations: List<ResourceOperationDetails>
)

data class ResourceOperationProperties(
    val action: Action? = null,
    val identifier: String? = null,
    val idempotencyKey: String? = null
)

data class ResourceOperationDetails(
    val id: String,
    val action: Action? = null,
    val identifier: String? = null,
    val idempotencyKey: String? = null
)

enum class Action(val value: String) {
    CREATE("create"),
    READ("read"),
    UPDATE("update"),
    DELETE("delete"),
    UPSERT("upsert");

    companion object {
        fun parse(value: String, location: String): Action =
            entries.firstOrNull { it.value == value }
                ?: invalid(location, "must be one of ${entries.joinToString { it.value }}")
    }
}

/**
 * Resource names are cache identities. Reusing a name at another base path intentionally shares that resource;
 * repeating the same name and base path is rejected because it splits one declaration ambiguously.
 */
internal fun resourceDetailsFrom(config: Map<String, Any?>, specificationPath: String): List<ResourceDetails> {
    if ("resources" !in config) return emptyList()
    val rawResources = config["resources"] ?: invalid("spec '$specificationPath'#/resources", "must be a list")
    return ResourceDetailsReader(specificationPath).read(rawResources)
}

private class ResourceDetailsReader(private val specificationPath: String) {
    private val resourceDeclarations = mutableSetOf<Pair<String, String>>()
    private val operationBindings = mutableMapOf<Pair<String, String>, String>()

    fun read(rawResources: Any): List<ResourceDetails> = rawResources.asList(location("/resources"))
        .mapIndexed(::readResource)

    private fun readResource(index: Int, rawEntry: Any?): ResourceDetails {
        val entryLocation = location("/resources/$index")
        val entry = rawEntry.asObject(entryLocation).only("resource", location = entryLocation)
        val resourceLocation = "$entryLocation/resource"
        val resource = entry["resource"].asObject(resourceLocation)
            .only("name", "basePath", "commonOperationProperties", "operations", location = resourceLocation)

        val name = resource.requiredString("name", resourceLocation)
        val basePath = resource.requiredString("basePath", resourceLocation)
        if (!basePath.startsWith('/')) invalid("$resourceLocation/basePath", "must start with '/'")

        val normalizedBasePath = basePath.trimEnd('/').ifEmpty { "/" }
        if (!resourceDeclarations.add(name to normalizedBasePath)) {
            invalid(resourceLocation, "resource '$name' is already declared for basePath '$normalizedBasePath'")
        }

        val commonProperties = resource["commonOperationProperties"]?.let { rawProperties ->
            rawProperties.asObject("$resourceLocation/commonOperationProperties")
                .only("action", "identifier", "idempotencyKey", location = "$resourceLocation/commonOperationProperties")
                .toOperationProperties("$resourceLocation/commonOperationProperties")
        } ?: ResourceOperationProperties()

        val operationsLocation = "$resourceLocation/operations"
        val operations = resource["operations"].asList(operationsLocation)
        if (operations.isEmpty()) invalid(operationsLocation, "must contain at least one operation")

        return ResourceDetails(
            name = name,
            basePath = basePath,
            commonOperationProperties = commonProperties,
            operations = operations.mapIndexed { operationIndex, operation ->
                readOperation(operation, resourceLocation, operationIndex, name, normalizedBasePath)
            }
        )
    }

    private fun readOperation(
        rawEntry: Any?,
        resourceLocation: String,
        index: Int,
        resourceName: String,
        basePath: String
    ): ResourceOperationDetails {
        val entryLocation = "$resourceLocation/operations/$index"
        val operationEntry = rawEntry.asObject(entryLocation).only("operation", location = entryLocation)
        val operationLocation = "$entryLocation/operation"
        val operation = operationEntry["operation"].asObject(operationLocation)
            .only("id", "action", "identifier", "idempotencyKey", location = operationLocation)

        val id = operation.requiredString("id", operationLocation)
        val binding = basePath to id
        val previousResource = operationBindings.putIfAbsent(binding, resourceName)
        if (previousResource != null) {
            invalid("$operationLocation/id", "operation '$id' under basePath '$basePath' is already assigned to resource '$previousResource'")
        }

        return operation.toOperationProperties(operationLocation).let { properties ->
            ResourceOperationDetails(
                id = id,
                action = properties.action,
                identifier = properties.identifier,
                idempotencyKey = properties.idempotencyKey
            )
        }
    }

    private fun location(pointer: String) = "spec '$specificationPath'#$pointer"
}

private fun Map<String, Any?>.toOperationProperties(location: String): ResourceOperationProperties =
    ResourceOperationProperties(
        action = optionalString("action", location)?.let { Action.parse(it, "$location/action") },
        identifier = optionalString("identifier", location),
        idempotencyKey = optionalString("idempotencyKey", location)
    )

private fun Any?.asObject(location: String): Map<String, Any?> = (this as? Map<*, *>)
    ?.entries
    ?.associate { (key, value) ->
        (key as? String ?: invalid(location, "contains a non-string property name")) to value
    }
    ?: invalid(location, "must be an object")

private fun Any?.asList(location: String): List<*> = this as? List<*>
    ?: invalid(location, "must be a list")

private fun Map<String, Any?>.only(vararg allowed: String, location: String): Map<String, Any?> {
    keys.firstOrNull { it !in allowed }?.let { key ->
        invalid("$location/$key", "is unsupported; supported properties are ${allowed.joinToString()}")
    }
    return this
}

private fun Map<String, Any?>.requiredString(key: String, location: String): String =
    optionalString(key, location)?.takeIf(String::isNotBlank)
        ?: invalid("$location/$key", "is required and must be a non-empty string")

private fun Map<String, Any?>.optionalString(key: String, location: String): String? {
    if (key !in this) return null
    val value = this[key] as? String ?: invalid("$location/$key", "must be a string")
    if (value.isBlank()) invalid("$location/$key", "must be a non-empty string")
    return value
}

private fun invalid(location: String, reason: String): Nothing =
    throw ContractException("Invalid stateful mock config at $location: $reason")
