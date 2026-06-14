package io.specmatic.core

internal fun Result.withNestedObjectQueryKeyBreadcrumb(nestedObjectQueryParam: NestedObjectQueryParam?): Result {
    if (nestedObjectQueryParam == null) return this

    return when (this) {
        is Result.Failure -> collapseSchemaPathBreadcrumbToQueryKey(NestedObjectQueryBreadcrumbPath(nestedObjectQueryParam))
        else -> this
    }
}

private fun Result.Failure.collapseSchemaPathBreadcrumbToQueryKey(
    path: NestedObjectQueryBreadcrumbPath
): Result.Failure {
    // Object matching records schema breadcrumbs like errors -> [0] -> code.
    // Query examples need one serialized wire-key breadcrumb at the leaf instead.
    val breadcrumbToken = breadCrumb.toQueryObjectPathTokenOrNull()
    val pathIncludingCurrentBreadcrumb = path.including(breadcrumbToken)
    val updatedCauses = causes.withCollapsedQueryKeyBreadcrumbs(pathIncludingCurrentBreadcrumb)
    val queryKeyBreadcrumb = pathIncludingCurrentBreadcrumb.queryKeyBreadcrumb

    return when {
        updatedCauses.hasNestedFailures() -> withIntermediateSchemaBreadcrumbCleared(breadcrumbToken, updatedCauses)
        queryKeyBreadcrumb != null -> withLeafQueryKeyBreadcrumb(queryKeyBreadcrumb, updatedCauses)
        else -> copy(causes = updatedCauses)
    }
}

private data class NestedObjectQueryBreadcrumbPath(
    private val nestedObjectQueryParam: NestedObjectQueryParam,
    private val tokens: List<QueryObjectPathToken> = emptyList()
) {
    fun including(token: QueryObjectPathToken?): NestedObjectQueryBreadcrumbPath {
        return token?.let { copy(tokens = tokens + it) } ?: this
    }

    val queryKeyBreadcrumb: String?
        get() {
            if (tokens.isEmpty()) return null

            return nestedObjectQueryParam.queryKeyFor(QueryObjectPath(tokens))
        }
}

private fun List<Result.FailureCause>.withCollapsedQueryKeyBreadcrumbs(
    path: NestedObjectQueryBreadcrumbPath
): List<Result.FailureCause> {
    return map { failureCause ->
        failureCause.copy(cause = failureCause.cause?.collapseSchemaPathBreadcrumbToQueryKey(path))
    }
}

private fun List<Result.FailureCause>.hasNestedFailures(): Boolean {
    return any { it.cause != null }
}

private fun Result.Failure.withIntermediateSchemaBreadcrumbCleared(
    breadcrumbToken: QueryObjectPathToken?,
    updatedCauses: List<Result.FailureCause>
): Result.Failure {
    return copy(breadCrumb = if (breadcrumbToken == null) breadCrumb else "", causes = updatedCauses)
}

private fun Result.Failure.withLeafQueryKeyBreadcrumb(
    queryKeyBreadcrumb: String,
    updatedCauses: List<Result.FailureCause>
): Result.Failure {
    return copy(breadCrumb = queryKeyBreadcrumb, causes = updatedCauses)
}

private fun String.toQueryObjectPathTokenOrNull(): QueryObjectPathToken? {
    val arrayIndex = removePrefix("[").removeSuffix("]").toIntOrNull()
    if (startsWith("[") && endsWith("]") && arrayIndex != null) return QueryObjectPathToken.Index(arrayIndex)
    if (isBlank() || contains(".")) return null

    return QueryObjectPathToken.Property(this)
}

internal fun Result.Failure.withNestedObjectPathBreadcrumb(
    nestedObjectQueryParam: NestedObjectQueryParam,
    path: QueryObjectPath
): Result.Failure {
    return breadCrumb(nestedObjectQueryParam.queryKeyFor(path))
}
