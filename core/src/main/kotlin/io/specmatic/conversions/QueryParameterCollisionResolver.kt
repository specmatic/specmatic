package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.FormExplodedObjectQueryParam
import io.specmatic.core.QueryParameterCollisionGroup
import io.specmatic.core.QueryParameterCollisionOwner
import io.specmatic.core.QueryParameterCollisionOwnerKind
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.QueryParameterArrayPattern
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.resolvedHop

internal data class QueryParameterPatternSource(val parameterName: String, val propertyName: String? = null) {
    val isFormExplodedObjectProperty: Boolean = propertyName != null
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
    val collisionGroupsByWireKey: Map<String, QueryParameterCollisionGroup>
)

internal fun resolveQueryParameterCollisions(
    entries: List<QueryParameterPatternEntry>,
    formExplodedObjectQueryParams: List<FormExplodedObjectQueryParam>,
    patterns: Map<String, Pattern>
): QueryParameterCollisionResolution {
    val collisionEntriesByWireKey = entries.groupBy(QueryParameterPatternEntry::wireKey).filterValues { it.size > 1 }
    val collisionGroupsByWireKey = collisionEntriesByWireKey.mapValues { (wireKey, collidingEntries) ->
        toQueryParameterCollisionGroup(wireKey, collidingEntries)
    }

    collisionEntriesByWireKey.forEach { (_, collidingEntries) ->
        recordQueryParameterTypeCollisionIfNeeded(collidingEntries, patterns)
    }

    val nonAuthoritativeObjectPropertiesByParameter = nonAuthoritativeFormExplodedObjectPropertiesByParameter(collisionEntriesByWireKey)
    val effectiveEntries = entries.filter { entry ->
        entry.wireKey !in collisionEntriesByWireKey || collisionEntriesByWireKey.getValue(entry.wireKey).first() == entry
    }

    return QueryParameterCollisionResolution(
        effectiveEntries = effectiveEntries,
        formExplodedObjectQueryParams = formExplodedObjectQueryParams.map {
            it.withoutProperties(nonAuthoritativeObjectPropertiesByParameter[it.parameterName].orEmpty())
        },
        collisionGroupsByWireKey = collisionGroupsByWireKey
    )
}

private fun nonAuthoritativeFormExplodedObjectPropertiesByParameter(collisionEntriesByWireKey: Map<String, List<QueryParameterPatternEntry>>): Map<String, Set<String>> {
    return collisionEntriesByWireKey.values
        .flatMap { entries -> entries.drop(1) }
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

private fun toQueryParameterCollisionGroup(wireKey: String, entries: List<QueryParameterPatternEntry>): QueryParameterCollisionGroup {
    val owners = entries.map(::toQueryParameterCollisionOwner)
    return QueryParameterCollisionGroup(
        wireKey = wireKey,
        owners = owners,
        authoritativeOwner = owners.first()
    )
}

private fun toQueryParameterCollisionOwner(entry: QueryParameterPatternEntry): QueryParameterCollisionOwner {
    return QueryParameterCollisionOwner(
        wireKey = entry.wireKey,
        sourceName = entry.source.displayName,
        kind = if (entry.source.isFormExplodedObjectProperty) {
            QueryParameterCollisionOwnerKind.FormExplodedObjectProperty
        } else {
            QueryParameterCollisionOwnerKind.ScalarParameter
        },
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

    val authoritativeEntry = entries.first()
    val ownerDetails = entries.joinToString(separator = "\n") { "- ${it.diagnosticDisplayName()}" }
    authoritativeEntry.collectorContext.record(
        message = "Query parameter wire key ${authoritativeEntry.wireKey} has conflicting schemas:\n$ownerDetails\nSpecmatic will use the first declared query parameter ${authoritativeEntry.source.displayName} as authoritative.",
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
