package io.specmatic.test.reports.coverage

import io.specmatic.core.report.closestMatchingEndpointFor
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CtrfOperationMetrics
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.API
import io.specmatic.test.TestResultRecord

data class CoverageContext(
    val tests: List<TestResultRecord>,
    val allSpecEndpoints: List<Endpoint>,
    val applicationEndpoints: List<API> = emptyList(),
    val endpointsApiAvailable: Boolean = false,
    val decisions: Map<Endpoint, List<Decision<*, OpenAPIOperation>>> = emptyMap(),
    val previousCoverageMetrics: Map<OpenAPIOperation, CtrfOperationMetrics> = emptyMap(),
) {
    private val skipDecisionsByOperation: Map<OpenAPIOperation, Sequence<Reasoning>> by lazy(LazyThreadSafetyMode.NONE) {
        decisions.entries.associate { (endpoint, endpointDecisions) ->
            val snapshots = endpointDecisions.asSequence().filterIsInstance<Decision.Skip<OpenAPIOperation>>().map { it.reasoning }
            endpoint.toOpenApiOperation() to snapshots
        }
    }

    fun getSkipDecisionsFor(operation: OpenAPIOperation): List<Reasoning> {
        return skipDecisionsByOperation.getOrDefault(operation, emptySequence()).toList()
    }

    fun specOperations(): Set<OpenAPIOperation> {
        return allSpecEndpoints.map { it.toOpenApiOperation() }.toSet()
    }

    fun previousRunMetricsFor(operation: OpenAPIOperation): CtrfOperationMetrics? {
        return previousCoverageMetrics[operation]
    }

    fun hasPreviousRunMetricsFor(operation: OpenAPIOperation): Boolean {
        return operation in previousCoverageMetrics
    }

    fun allCoverageOperations(): Set<OpenAPIOperation> {
        val specOperations = specOperations()
        val operationsDerivedFromTests = tests
            .flatMap { it.operations }
            .filterIsInstance<OpenAPIOperation>()
            .toSet()
        val missingInSpecOperations = missingInSpecOperations(specOperations)

        return specOperations + operationsDerivedFromTests + missingInSpecOperations
    }

    private fun missingInSpecOperations(
        specOperations: Set<OpenAPIOperation>,
    ): Set<OpenAPIOperation> {
        if (!endpointsApiAvailable) {
            return emptySet()
        }

        return applicationEndpoints
            .filterNot { applicationEndpoint ->
                specOperations.any { specOperation ->
                    specOperation.path == applicationEndpoint.path &&
                        specOperation.method.equals(applicationEndpoint.method, ignoreCase = true)
                }
            }
            .mapNotNull { api ->
                closestMatchingEndpointFor(api.path, api.method, allSpecEndpoints)
                    ?: return@mapNotNull null

                OpenAPIOperation(
                    path = api.path,
                    method = api.method,
                    contentType = null,
                    responseCode = 0,
                    protocol = SpecmaticProtocol.HTTP,
                    responseContentType = null,
                )
            }
            .toSet()
    }
}
