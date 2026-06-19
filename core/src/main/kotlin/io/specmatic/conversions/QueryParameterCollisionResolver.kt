package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.FormExplodedObjectQueryParam
import io.specmatic.core.NestedObjectQueryParam
import io.specmatic.core.QueryParameterCollisionGroup
import io.specmatic.core.QueryParameterCollisionOwner
import io.specmatic.core.QueryParameterCollisionOwnerKind
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.PossibleJsonObjectPatternContainer
import io.specmatic.core.pattern.QueryParameterArrayPattern
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.resolvedHop
import io.specmatic.core.pattern.withoutOptionality

internal data class QueryParameterPatternSource(
    val parameterName: String,
    val propertyName: String? = null,
    val kind: QueryParameterCollisionOwnerKind = if (propertyName == null) {
        QueryParameterCollisionOwnerKind.ScalarParameter
    } else {
        QueryParameterCollisionOwnerKind.FormExplodedObjectProperty
    }
) {
    val displayName: String = propertyName?.let { "$parameterName.$it" } ?: parameterName
}

internal data class QueryParameterPatternEntry(
    val key: String,
    val wireKey: String,
    val pattern: Pattern,
    val source: QueryParameterPatternSource,
    val collectorContext: CollectorContext,
    val pointer: String? = null
)

internal data class QueryParameterCollisionResolution(
    val effectiveEntries: List<QueryParameterPatternEntry>,
    val formExplodedObjectQueryParams: List<FormExplodedObjectQueryParam>,
    val nestedObjectQueryParams: List<NestedObjectQueryParam>,
    val collisionGroupsByWireKey: Map<String, QueryParameterCollisionGroup>
)

internal fun resolveQueryParameterCollisions(
    entries: List<QueryParameterPatternEntry>,
    collisionEntries: List<QueryParameterPatternEntry> = entries,
    formExplodedObjectQueryParams: List<FormExplodedObjectQueryParam>,
    nestedObjectQueryParams: List<NestedObjectQueryParam>,
    patterns: Map<String, Pattern>
): QueryParameterCollisionResolution {
    val collisionEntriesByWireKey = collisionEntries.groupBy(QueryParameterPatternEntry::wireKey).filterValues { it.size > 1 }
    val collisionGroupsByWireKey = collisionEntriesByWireKey.mapValues { (wireKey, collidingEntries) ->
        toQueryParameterCollisionGroup(wireKey, collidingEntries)
    }

    collisionEntriesByWireKey.forEach { (_, collidingEntries) ->
        recordQueryParameterTypeCollisionIfNeeded(collidingEntries, patterns)
    }

    val authoritativeEntriesByWireKey = collisionEntriesByWireKey.mapValues { (_, entries) -> entries.last() }
    val nonAuthoritativeFormExplodedObjectPropertiesByParameter = nonAuthoritativeObjectPropertiesByParameter(
        collisionEntriesByWireKey,
        QueryParameterCollisionOwnerKind.FormExplodedObjectProperty
    )
    val nonAuthoritativeNestedObjectPropertiesByParameter = nonAuthoritativeObjectPropertiesByParameter(
        collisionEntriesByWireKey,
        QueryParameterCollisionOwnerKind.NestedObjectProperty
    )
    val effectiveEntries = entries.filter { entry ->
        authoritativeEntriesByWireKey[entry.wireKey]?.let { it == entry } ?: true
    }.map { entry ->
        entry.withoutNestedObjectProperties(
            nonAuthoritativeNestedObjectPropertiesByParameter[entry.source.parameterName].orEmpty(),
            patterns
        )
    }

    return QueryParameterCollisionResolution(
        effectiveEntries = effectiveEntries,
        formExplodedObjectQueryParams = formExplodedObjectQueryParams.map {
            it.withoutProperties(nonAuthoritativeFormExplodedObjectPropertiesByParameter[it.parameterName].orEmpty())
        },
        nestedObjectQueryParams = nestedObjectQueryParams.map {
            it.withoutRootProperties(nonAuthoritativeNestedObjectPropertiesByParameter[it.parameterName].orEmpty())
        },
        collisionGroupsByWireKey = collisionGroupsByWireKey
    )
}

private fun nonAuthoritativeObjectPropertiesByParameter(
    collisionEntriesByWireKey: Map<String, List<QueryParameterPatternEntry>>,
    ownerKind: QueryParameterCollisionOwnerKind
): Map<String, Set<String>> {
    return collisionEntriesByWireKey.values
        .flatMap { entries -> entries.dropLast(1) }
        .filter { entry -> entry.source.kind == ownerKind }
        .mapNotNull { entry ->
            entry.source.propertyName?.let { propertyName ->
                entry.source.parameterName to propertyName
            }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, propertyNames) -> propertyNames.toSet() }
}

private fun FormExplodedObjectQueryParam.withoutProperties(propertyNames: Set<String>): FormExplodedObjectQueryParam {
    if (propertyNames.isEmpty()) return this

    return copy(
        propertyKeys = propertyKeys - propertyNames,
        requiredPropertyKeys = requiredPropertyKeys - propertyNames
    )
}

private fun QueryParameterPatternEntry.withoutNestedObjectProperties(propertyNames: Set<String>, patterns: Map<String, Pattern>): QueryParameterPatternEntry {
    if (propertyNames.isEmpty()) return this

    return copy(pattern = pattern.withoutRootProperties(propertyNames, patterns))
}

private fun Pattern.withoutRootProperties(propertyNames: Set<String>, patterns: Map<String, Pattern>): Pattern {
    if (propertyNames.isEmpty()) return this

    val resolver = Resolver(newPatterns = patterns)
    return when (this) {
        is QueryParameterScalarPattern -> QueryParameterScalarPattern(pattern.withoutRootProperties(propertyNames, patterns))
        is JSONObjectPattern -> withoutRootProperties(propertyNames)
        is PossibleJsonObjectPatternContainer -> jsonObjectPattern(resolver)?.withoutRootProperties(propertyNames) ?: this
        else -> this
    }
}

private fun JSONObjectPattern.withoutRootProperties(propertyNames: Set<String>): JSONObjectPattern {
    return copy(pattern = pattern.filterKeys { key -> withoutOptionality(key) !in propertyNames })
}

private fun toQueryParameterCollisionGroup(wireKey: String, entries: List<QueryParameterPatternEntry>): QueryParameterCollisionGroup {
    val owners = entries.map(::toQueryParameterCollisionOwner)
    return QueryParameterCollisionGroup(
        wireKey = wireKey,
        owners = owners,
        authoritativeOwner = owners.last()
    )
}

private fun toQueryParameterCollisionOwner(entry: QueryParameterPatternEntry): QueryParameterCollisionOwner {
    return QueryParameterCollisionOwner(
        wireKey = entry.wireKey,
        sourceName = entry.source.displayName,
        kind = entry.source.kind,
        pattern = entry.pattern,
        required = !entry.key.endsWith("?"),
        parameterName = entry.source.parameterName,
        propertyName = entry.source.propertyName,
        pointer = entry.pointer
    )
}

private fun recordQueryParameterTypeCollisionIfNeeded(entries: List<QueryParameterPatternEntry>, patterns: Map<String, Pattern>) {
    val resolvedPatterns = entries.map { normalizedQueryParameterPattern(it.pattern, patterns) }
    if (resolvedPatterns.distinct().size == 1) return

    val authoritativeEntry = entries.last()
    val ownerDetails = entries.joinToString(separator = "\n") { "- ${it.diagnosticDisplayName()}" }
    authoritativeEntry.collectorContext.record(
        message = "Query parameter wire key ${authoritativeEntry.wireKey} has conflicting schemas:\n$ownerDetails\nSpecmatic will use the last declared query parameter ${authoritativeEntry.source.displayName} as authoritative.",
        isWarning = true,
        ruleViolation = OpenApiLintViolations.QUERY_PARAMETER_TYPE_COLLISION
    )
}

private fun QueryParameterPatternEntry.diagnosticDisplayName(): String {
    return pointer?.let { "${source.displayName} at ${it.toBreadcrumbPath()}" } ?: source.displayName
}

private fun String.toBreadcrumbPath(): String {
    val decodedSegments = trimStart('/')
        .split('/')
        .filter(String::isNotBlank)
        .map { it.replace("~1", "/").replace("~0", "~") }

    return decodedSegments.fold("") { path, segment ->
        when {
            path.isBlank() -> segment
            segment.toIntOrNull() != null -> "$path[$segment]"
            else -> "$path.$segment"
        }
    }
}

private fun normalizedQueryParameterPattern(pattern: Pattern, patterns: Map<String, Pattern>): Pattern {
    val resolver = Resolver(newPatterns = patterns)
    return when (pattern) {
        is QueryParameterScalarPattern -> QueryParameterScalarPattern(resolvedHop(pattern.pattern, resolver))
        is QueryParameterArrayPattern -> QueryParameterArrayPattern(pattern.pattern.map { resolvedHop(it, resolver) }, pattern.parameterName)
        else -> resolvedHop(pattern, resolver)
    }
}
