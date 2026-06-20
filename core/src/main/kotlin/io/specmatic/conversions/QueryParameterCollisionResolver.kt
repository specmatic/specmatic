package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.QueryParameterRuntimeEntry
import io.specmatic.core.QueryParameterRuntimeSource
import io.specmatic.core.QueryParameterSourceKind
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.QueryParameterArrayPattern
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.resolvedHop

internal data class QueryParameterPatternSource(
    val parameterName: String,
    val propertyName: String? = null,
    val kind: QueryParameterSourceKind = if (propertyName == null) {
        QueryParameterSourceKind.ScalarParameter
    } else {
        QueryParameterSourceKind.FormExplodedObjectProperty
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

internal fun QueryParameterPatternEntry.toRuntimeEntry(): QueryParameterRuntimeEntry {
    return QueryParameterRuntimeEntry(
        key = key,
        wireKey = wireKey,
        pattern = pattern,
        source = QueryParameterRuntimeSource(
            parameterName = source.parameterName,
            propertyName = source.propertyName,
            kind = source.kind
        ),
        pointer = pointer
    )
}

internal fun recordQueryParameterTypeCollisions(
    entries: List<QueryParameterPatternEntry>,
    patterns: Map<String, Pattern>
) {
    entries.groupBy(QueryParameterPatternEntry::wireKey)
        .filterValues { it.size > 1 }
        .values
        .forEach { collidingEntries ->
            recordQueryParameterTypeCollisionIfNeeded(collidingEntries, patterns)
        }
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
