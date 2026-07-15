package io.specmatic.test

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.ApplicationApiSource
import io.specmatic.core.DEFAULT_SWAGGER_SPEC_YAML_PATH
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.KeyData
import io.specmatic.core.log.ignoreLog
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.test.reports.coverage.OpenApiCoverage

internal fun interface ApplicationApiSourceClient {
    fun get(applicationApiSourceUrl: String): HttpResponse
}

internal fun interface ApplicationApiSourceKeyDataProvider {
    fun get(applicationApiSourceUrl: String): KeyData?
}

internal class HttpApplicationApiSourceClient(
    private val prettyPrint: Boolean,
    private val keyDataProvider: ApplicationApiSourceKeyDataProvider,
) : ApplicationApiSourceClient {
    override fun get(applicationApiSourceUrl: String): HttpResponse {
        return HttpClient(
            applicationApiSourceUrl,
            log = ignoreLog,
            prettyPrint = prettyPrint,
            keyData = keyDataProvider.get(applicationApiSourceUrl),
        ).use { httpClient ->
            httpClient.execute(HttpRequest("GET"))
        }
    }
}

internal class ApplicationApiDiscovery(
    private val sourceClient: ApplicationApiSourceClient,
) {
    fun discover(sources: List<ApplicationApiSource>, coverage: OpenApiCoverage) {
        val uniqueSources = sources.distinct()
        val sourceFetches = uniqueSources.map { source ->
            ApplicationApiSourceFetch(source, fetchApplicationApisFrom(source))
        }

        sourceFetches.forEach { sourceFetch ->
            when (val result = sourceFetch.result) {
                is ApplicationApiFetchResult.Success -> coverage.addAPIs(result.apis)
                is ApplicationApiFetchResult.Failure -> reportApplicationApiFetchFailure(sourceFetch.source, result)
            }
        }

        val anySourceAvailable = sourceFetches.any { it.result is ApplicationApiFetchResult.Success }
        coverage.setEndpointsAPIFlag(anySourceAvailable)

        val anyExplicitSourceConfigured = uniqueSources.any { it.isExplicitlyConfigured }
        if (!anySourceAvailable && !anyExplicitSourceConfigured) {
            logger.boundary()
            logger.log("No application API source was exposed by the application, so cannot calculate actual coverage")
        }
    }

    private fun fetchApplicationApisFrom(source: ApplicationApiSource): ApplicationApiFetchResult {
        return try {
            when (source) {
                is ApplicationApiSource.Actuator -> fetchApplicationApisFromActuator(source)
                is ApplicationApiSource.Swagger -> fetchApplicationApisFromOpenApiDocument(source.url)
                is ApplicationApiSource.SwaggerUi -> fetchApplicationApisFromOpenApiDocument(
                    source.url.trimEnd('/') + DEFAULT_SWAGGER_SPEC_YAML_PATH
                )
            }
        } catch (exception: Throwable) {
            ApplicationApiFetchResult.Failure(exceptionCauseMessage(exception))
        }
    }

    private fun fetchApplicationApisFromOpenApiDocument(swaggerDocUrl: String): ApplicationApiFetchResult {
        val response = sourceClient.get(swaggerDocUrl)
        if (response.status != 200) {
            return ApplicationApiFetchResult.Failure("Received HTTP status ${response.status}")
        }

        val featureFromJson = OpenApiSpecification.fromYAML(response.body.toStringLiteral(), "").toFeature()
        return ApplicationApiFetchResult.Success(
            featureFromJson.scenarios.map { scenario ->
                API(method = scenario.method, path = convertPathParameterStyle(scenario.path))
            }.distinct()
        )
    }

    private fun fetchApplicationApisFromActuator(source: ApplicationApiSource.Actuator): ApplicationApiFetchResult {
        val response = sourceClient.get(source.url)
        if (response.status != 200) {
            return ApplicationApiFetchResult.Failure("Received HTTP status ${response.status}")
        }

        logger.debug(response.toLogString())
        val endpointData = response.body as JSONObjectValue
        return ApplicationApiFetchResult.Success(
            endpointData.getJSONObject("contexts").entries.flatMap { entry ->
                val mappings =
                    (entry.value as JSONObjectValue).findFirstChildByPath("mappings.dispatcherServlets.dispatcherServlet") as JSONArrayValue
                mappings.list.map { it as JSONObjectValue }.filter {
                    it.findFirstChildByPath("details.handlerMethod.className")?.toStringLiteral()
                        ?.contains("springframework") != true
                }.flatMap {
                    val methods = it.findFirstChildByPath("details.requestMappingConditions.methods") as JSONArrayValue?
                    val paths = it.findFirstChildByPath("details.requestMappingConditions.patterns") as JSONArrayValue?

                    if (methods != null && paths != null) {
                        methods.list.flatMap { method ->
                            paths.list.map { path ->
                                API(method.toStringLiteral(), path.toStringLiteral())
                            }
                        }
                    } else {
                        emptyList()
                    }
                }
            }
        )
    }

    private fun reportApplicationApiFetchFailure(
        source: ApplicationApiSource,
        failure: ApplicationApiFetchResult.Failure,
    ) {
        if (source.isExplicitlyConfigured) {
            logger.log("WARNING: Could not use ${source.displayName()} at ${source.url}: ${failure.reason}")
        } else {
            logger.debug("Could not use inferred ${source.displayName()} at ${source.url}: ${failure.reason}")
        }
    }

    private fun ApplicationApiSource.displayName(): String {
        val sourceType = when (this) {
            is ApplicationApiSource.Actuator -> "actuator URL"
            is ApplicationApiSource.Swagger -> "Swagger URL"
            is ApplicationApiSource.SwaggerUi -> "Swagger UI base URL"
        }
        return sourceType
    }

    private sealed interface ApplicationApiFetchResult {
        data class Success(val apis: List<API>) : ApplicationApiFetchResult
        data class Failure(val reason: String) : ApplicationApiFetchResult
    }

    private data class ApplicationApiSourceFetch(
        val source: ApplicationApiSource,
        val result: ApplicationApiFetchResult,
    )
}
