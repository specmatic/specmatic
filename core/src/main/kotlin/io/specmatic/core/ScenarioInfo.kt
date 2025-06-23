package io.specmatic.core

import io.specmatic.conversions.ApiSpecification
import io.specmatic.conversions.OperationMetadata
import io.specmatic.core.pattern.*
import io.specmatic.core.value.Value

data class ScenarioInfo(
    val scenarioName: String = "",
    val httpRequestPattern: HttpRequestPattern = HttpRequestPattern(),
    val httpResponsePattern: HttpResponsePattern = HttpResponsePattern(),
    val expectedServerState: Map<String, Value> = emptyMap(),
    val patterns: Map<String, Pattern> = emptyMap(),
    val fixtures: Map<String, Value> = emptyMap(),
    val examples: List<Examples> = emptyList(),
    val ignoreFailure: Boolean = false,
    val references: Map<String, References> = emptyMap(),
    val bindings: Map<String, String> = emptyMap(),
    val isGherkinScenario: Boolean = false,
    val sourceProvider:String? = null,
    val sourceRepository:String? = null,
    val sourceRepositoryBranch:String? = null,
    val specification:String? = null,
    val serviceType:String? = null,
    val operationMetadata: OperationMetadata? = null
) {

    fun matchesGherkinWrapperPath(scenarioInfos: List<ScenarioInfo>, apiSpecification: ApiSpecification): List<ScenarioInfo> =
        scenarioInfos.filter { openApiScenarioInfo ->
            val pathPatternFromOpenApi = openApiScenarioInfo.httpRequestPattern.httpPathPattern!!.pathSegmentPatterns
            val pathPatternFromWrapper = this.httpRequestPattern.httpPathPattern!!.pathSegmentPatterns

            if(pathPatternFromOpenApi.size != pathPatternFromWrapper.size)
                return@filter false

            val resolver = Resolver(newPatterns = openApiScenarioInfo.patterns)
            val zipped = pathPatternFromOpenApi.zip(pathPatternFromWrapper)

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
