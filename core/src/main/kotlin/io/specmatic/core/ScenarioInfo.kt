package io.specmatic.core

import io.ktor.http.HttpStatusCode
import io.specmatic.conversions.ApiSpecification
import io.specmatic.conversions.OperationMetadata
import io.specmatic.core.pattern.*
import io.specmatic.core.value.Value
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType

data class ScenarioInfo(
    val scenarioName: String = "",
    val httpRequestPattern: HttpRequestPattern = HttpRequestPattern(),
    val httpResponsePattern: HttpResponsePattern = HttpResponsePattern(),
    val patterns: Map<String, Pattern> = emptyMap(),
    val fixtures: Map<String, Value> = emptyMap(),
    val examples: List<Examples> = emptyList(),
    val ignoreFailure: Boolean = false,
    val isGherkinScenario: Boolean = false,
    val sourceProvider: String? = null,
    val sourceRepository: String? = null,
    val sourceRepositoryBranch: String? = null,
    val specification: String? = null,
    val protocol: SpecmaticProtocol,
    val specType: SpecType,
    val operationMetadata: OperationMetadata? = null,
    val sourceLocations: Map<String, SourceLocation> = emptyMap(),
    val operationSourcePointer: String? = null,
    val undeclaredRequestVariantMetadata: UndeclaredRequestVariantMetadata = UndeclaredRequestVariantMetadata()
) {

    fun matchesGherkinWrapperPath(scenarioInfos: List<ScenarioInfo>, apiSpecification: ApiSpecification, resolver: Resolver): List<ScenarioInfo> =
        scenarioInfos.filter { openApiScenarioInfo ->
            val openApiPathPattern = openApiScenarioInfo.httpRequestPattern.httpPathPattern!!
            val wrapperPathPattern = this.httpRequestPattern.httpPathPattern!!
            val zipped = openApiPathPattern.zipWithExpandedPathSegments(wrapperPathPattern, resolver) ?: return@filter false

            val resolver = Resolver(newPatterns = openApiScenarioInfo.patterns)
            zipped.all { (openapiURLPart: URLPathSegmentPattern, wrapperURLPart: URLPathSegmentPattern) ->
                val openapiType = if(openapiURLPart.pattern is ExactValuePattern) "exact" else "pattern"
                val wrapperType = if(wrapperURLPart.pattern is ExactValuePattern) "exact" else "pattern"

                when(Pair(openapiType, wrapperType)) {
                    Pair("exact", "exact") -> apiSpecification.exactValuePatternsAreEqual(openapiURLPart, wrapperURLPart)
                    Pair("exact", "pattern") -> false
                    Pair("pattern", "exact") -> {
                        try {
                            apiSpecification.patternMatchesExact(
                                wrapperURLPart,
                                openapiURLPart,
                                resolver
                            )
                        } catch(e: Throwable) {
                            false
                        }
                    }
                    Pair("pattern", "pattern") -> {
                        val valueFromOpenapi = openapiURLPart.pattern.generate(Resolver(newPatterns = openApiScenarioInfo.patterns))
                        val valueFromWrapper = wrapperURLPart.pattern.generate(Resolver(newPatterns = this.patterns))

                        valueFromOpenapi.javaClass == valueFromWrapper.javaClass
                    }
                    else -> false
                }
            }
        }
}

data class UndeclaredRequestVariantMetadata(
    val responseStatus: Int? = null,
    val methodsForPath: Set<String> = emptySet(),
    val requestContentTypesForOperation: Set<String> = emptySet()
) {
    fun forResponseStatus(responseStatus: Int): UndeclaredRequestVariantMetadata {
        return when (responseStatus) {
            HttpStatusCode.MethodNotAllowed.value,
            HttpStatusCode.UnsupportedMediaType.value -> copy(responseStatus = responseStatus)
            else -> UndeclaredRequestVariantMetadata()
        }
    }

    fun hasUndeclaredRequestVariantResponse(): Boolean {
        return when (responseStatus) {
            HttpStatusCode.MethodNotAllowed.value,
            HttpStatusCode.UnsupportedMediaType.value -> true
            else -> false
        }
    }
}
