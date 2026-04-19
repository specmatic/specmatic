package io.specmatic.core.report

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord

fun TestResultRecord.toCtrfSpecConfigOrNull(): CtrfSpecConfig? {
    val specification = specification ?: return null

    return CtrfSpecConfig(
        protocol = SpecmaticProtocol.HTTP.key,
        specType = specType.value,
        specification = specification,
        sourceProvider = sourceProvider,
        repository = repository,
        branch = branch ?: "main",
    )
}

fun specConfigFor(
    operation: OpenAPIOperation,
    coverageStatus: CoverageStatus,
    attemptRecords: List<TestResultRecord>,
    allSpecEndpoints: List<SpecEndpoint>,
): CtrfSpecConfig {
    exactMatchingEndpoint(operation, allSpecEndpoints)?.let { return it.toCtrfSpecConfig() }
    attemptRecords.firstNotNullOfOrNull { it.toCtrfSpecConfigOrNull() }?.let { return it }

    if (coverageStatus == CoverageStatus.MISSING_IN_SPEC) {
        closestMatchingEndpointFor(operation.path, operation.method, allSpecEndpoints)
            ?.toCtrfSpecConfig()
            ?.let { return it }
    }

    throw IllegalArgumentException("Cannot determine spec config for $operation")
}

fun closestMatchingEndpointFor(path: String, method: String, endpoints: List<SpecEndpoint>): SpecEndpoint? {
    val endpointsWithSpecs = endpoints.filter { it.specification != null }
    if (endpointsWithSpecs.isEmpty()) {
        return null
    }

    val methodMatchedEndpoints = endpointsWithSpecs.filter {
        it.toOpenApiOperation().method.equals(method, ignoreCase = true)
    }
    val candidateEndpoints = methodMatchedEndpoints.ifEmpty { endpointsWithSpecs }

    return candidateEndpoints
        .maxWithOrNull(
            compareBy<SpecEndpoint> { commonPathPrefixSegments(path, it.toOpenApiOperation().path) }
                .thenBy { normalizedPathSegments(it.toOpenApiOperation().path).size }
        )
        ?: endpointsWithSpecs.first()
}

private fun exactMatchingEndpoint(
    operation: OpenAPIOperation,
    endpoints: List<SpecEndpoint>,
): SpecEndpoint? {
    return endpoints.firstOrNull { endpoint -> endpoint.toOpenApiOperation() == operation }
}

private fun commonPathPrefixSegments(leftPath: String, rightPath: String): Int {
    val leftSegments = normalizedPathSegments(leftPath)
    val rightSegments = normalizedPathSegments(rightPath)

    return leftSegments.zip(rightSegments).takeWhile { (left, right) -> left == right }.count()
}

private fun normalizedPathSegments(path: String): List<String> {
    return path.trim('/').split('/').filter { it.isNotBlank() }
}
