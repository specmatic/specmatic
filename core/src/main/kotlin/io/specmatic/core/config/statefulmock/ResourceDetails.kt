package io.specmatic.core.config.statefulmock

import io.specmatic.core.pattern.ContractException

data class ResourceDetails(
    val name: String,
    val basePath: String,
    val commonOperationProperties: ResourceOperationProperties = ResourceOperationProperties(),
    val operations: List<ResourceOperationDetails>
)

data class ResourceOperationProperties(
    val action: StatefulMockAction? = null,
    val identifier: String? = null,
    val idempotencyKey: String? = null
)

data class ResourceOperationDetails(
    val id: String,
    val action: StatefulMockAction? = null,
    val identifier: String? = null,
    val idempotencyKey: String? = null
)

enum class StatefulMockAction(val value: String) {
    CREATE("create"),
    READ("read"),
    UPDATE("update"),
    DELETE("delete"),
    UPSERT("upsert");

    companion object {
        fun parse(value: String, location: String): StatefulMockAction =
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
    val resources = config["resources"] ?: invalid("spec '$specificationPath'#/resources", "must be a list")
    val configPath = "spec '$specificationPath'#/resources"
    val entries = resources as? List<*> ?: invalid(configPath, "must be a list")
    val resourceDeclarations = mutableSetOf<Pair<String, String>>()
    val operationBindings = mutableMapOf<Pair<String, String>, String>()

    return entries.mapIndexed { resourceIndex, resourceEntry ->
        val resourceLocation = "$configPath/$resourceIndex/resource"
        val resourceEntryMap = resourceEntry.asMap("$configPath/$resourceIndex")
        rejectUnknownKeys(resourceEntryMap, setOf("resource"), "$configPath/$resourceIndex")
        val resource = resourceEntryMap["resource"]
            ?.asMap(resourceLocation)
            ?: invalid(resourceLocation, "is required")

        rejectUnknownKeys(resource, setOf("name", "basePath", "commonOperationProperties", "operations"), resourceLocation)
        val name = resource.requiredString("name", resourceLocation)
        val basePath = resource.requiredString("basePath", resourceLocation)
        if (!basePath.startsWith('/')) invalid("$resourceLocation/basePath", "must start with '/'")

        val normalizedBasePath = if (basePath == "/") basePath else basePath.trimEnd('/')
        if (!resourceDeclarations.add(name to normalizedBasePath)) {
            invalid(resourceLocation, "resource '$name' is already declared for basePath '$normalizedBasePath'")
        }

        val commonProperties = if ("commonOperationProperties" in resource) {
            resource["commonOperationProperties"].asMap("$resourceLocation/commonOperationProperties")
                .toOperationProperties("$resourceLocation/commonOperationProperties")
        } else ResourceOperationProperties()

        val rawOperations = resource["operations"] as? List<*>
            ?: invalid("$resourceLocation/operations", "must be a list")
        if (rawOperations.isEmpty()) invalid("$resourceLocation/operations", "must contain at least one operation")

        val operations = rawOperations.mapIndexed { operationIndex, operationEntry ->
            val operationLocation = "$resourceLocation/operations/$operationIndex/operation"
            val operationEntryLocation = "$resourceLocation/operations/$operationIndex"
            val operationEntryMap = operationEntry.asMap(operationEntryLocation)
            rejectUnknownKeys(operationEntryMap, setOf("operation"), operationEntryLocation)
            val operation = operationEntryMap["operation"]
                ?.asMap(operationLocation)
                ?: invalid(operationLocation, "is required")

            rejectUnknownKeys(operation, setOf("id", "action", "identifier", "idempotencyKey"), operationLocation)
            val id = operation.requiredString("id", operationLocation)
            val binding = normalizedBasePath to id
            val previousResource = operationBindings.putIfAbsent(binding, name)
            if (previousResource != null) {
                invalid("$operationLocation/id", "operation '$id' under basePath '$normalizedBasePath' is already assigned to resource '$previousResource'")
            }

            ResourceOperationDetails(
                id = id,
                action = operation.optionalString("action", operationLocation)?.let { StatefulMockAction.parse(it, "$operationLocation/action") },
                identifier = operation.optionalString("identifier", operationLocation),
                idempotencyKey = operation.optionalString("idempotencyKey", operationLocation)
            )
        }

        ResourceDetails(name, basePath, commonProperties, operations)
    }
}

private fun Map<*, *>.toOperationProperties(location: String): ResourceOperationProperties {
    val stringMap = asStringKeyMap(location)
    rejectUnknownKeys(stringMap, setOf("action", "identifier", "idempotencyKey"), location)
    return ResourceOperationProperties(
        action = stringMap.optionalString("action", location)?.let { StatefulMockAction.parse(it, "$location/action") },
        identifier = stringMap.optionalString("identifier", location),
        idempotencyKey = stringMap.optionalString("idempotencyKey", location)
    )
}

private fun Any?.asMap(location: String): Map<String, Any?> = (this as? Map<*, *>)
    ?.asStringKeyMap(location)
    ?: invalid(location, "must be an object")

private fun Map<*, *>.asStringKeyMap(location: String): Map<String, Any?> = entries.associate { (key, value) ->
    (key as? String ?: invalid(location, "contains a non-string property name")) to value
}

private fun Map<String, Any?>.requiredString(key: String, location: String): String =
    optionalString(key, location)?.takeIf(String::isNotBlank)
        ?: invalid("$location/$key", "is required and must be a non-empty string")

private fun Map<String, Any?>.optionalString(key: String, location: String): String? {
    if (key !in this) return null
    val value = this[key] ?: invalid("$location/$key", "must be a string")
    val stringValue = value as? String ?: invalid("$location/$key", "must be a string")
    if (stringValue.isBlank()) invalid("$location/$key", "must be a non-empty string")
    return stringValue
}

private fun rejectUnknownKeys(values: Map<String, Any?>, allowed: Set<String>, location: String) {
    values.keys.firstOrNull { it !in allowed }?.let { key ->
        invalid("$location/$key", "is unsupported; supported properties are ${allowed.joinToString()}")
    }
}

private fun invalid(location: String, reason: String): Nothing =
    throw ContractException("Invalid stateful mock config at $location: $reason")
