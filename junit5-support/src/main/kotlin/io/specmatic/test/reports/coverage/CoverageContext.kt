package io.specmatic.test.reports.coverage

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.API
import io.specmatic.test.TestResultRecord

data class CoverageContext(
    val tests: List<TestResultRecord>,
    val allSpecEndpoints: List<Endpoint>,
    val applicationEndpoints: List<API> = emptyList(),
    val endpointsApiAvailable: Boolean = false,
) {
    fun specOperations(): Set<OpenAPIOperation> {
        return allSpecEndpoints.map { it.toOpenApiOperation() }.toSet()
    }

    fun allCoverageOperations(): Set<OpenAPIOperation> {
        val specOperations = specOperations()
        val operationsDerivedFromTests = tests
            .mapNotNull { it.toOpenApiOperationOrNull() }
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
