package io.specmatic.core.substitution

import io.specmatic.core.HttpRequest

internal object SubstitutionVariableExtractor {
    fun fromRequest(runningRequest: HttpRequest, originalRequest: HttpRequest): Map<String, String> {
        val variableValuesFromHeaders = SubstitutionVariableStoreUpdater.fromMap(
            originalMap = originalRequest.headers,
            runningMap = runningRequest.headers
        )

        val variableValuesFromRequestBody = SubstitutionVariableStoreUpdater.fromValues(
            originalValue = originalRequest.body,
            runningValue = runningRequest.body
        )

        val variableValuesFromQueryParams = SubstitutionVariableStoreUpdater.fromMap(
            originalMap = originalRequest.queryParams.asMap(),
            runningMap = runningRequest.queryParams.asMap()
        )

        val variableValuesFromPath = SubstitutionVariableStoreUpdater.fromPath(
            originalPath = originalRequest.path,
            runningPath = runningRequest.path
        )

        return variableValuesFromHeaders + variableValuesFromRequestBody + variableValuesFromQueryParams + variableValuesFromPath
    }
}
