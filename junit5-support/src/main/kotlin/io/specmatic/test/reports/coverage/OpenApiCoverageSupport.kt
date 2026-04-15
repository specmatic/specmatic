package io.specmatic.test.reports.coverage

import io.specmatic.core.report.OpenApiCoverageReportOperation
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import kotlin.math.roundToInt

internal fun Endpoint.toOpenApiOperation(): OpenAPIOperation {
    return OpenAPIOperation(
        path = path,
        method = soapAction ?: method,
        contentType = requestContentType,
        responseCode = responseStatus,
        protocol = protocol,
        responseContentType = responseContentType,
    )
}

internal fun Endpoint.toCtrfSpecConfig(): CtrfSpecConfig {
    return CtrfSpecConfig(
        protocol = protocol.key,
        specType = specType.value,
        specification = specification.orEmpty(),
        sourceProvider = sourceProvider,
        repository = sourceRepository,
        branch = sourceRepositoryBranch ?: "main",
    )
}

internal fun TestResultRecord.toOpenApiOperationOrNull(): OpenAPIOperation? {
    if (specification == null) return null
    return OpenAPIOperation(
        path = path,
        protocol = protocol,
        method = soapAction ?: method,
        contentType = requestContentType,
        responseCode = responseStatus,
        responseContentType = responseContentType,
    )
}

internal fun TestResultRecord.toCtrfSpecConfigOrNull(): CtrfSpecConfig? {
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

internal fun closestMatchingEndpointFor(path: String, method: String, endpoints: List<Endpoint>): Endpoint? {
    val endpointsWithSpecs = endpoints.filter { it.specification != null }
    if (endpointsWithSpecs.isEmpty()) {
        return null
    }

    val methodMatchedEndpoints = endpointsWithSpecs.filter { it.method.equals(method, ignoreCase = true) }
    val candidateEndpoints = methodMatchedEndpoints.ifEmpty { endpointsWithSpecs }

    return candidateEndpoints
        .maxWithOrNull(
            compareBy<Endpoint> { commonPathPrefixSegments(path, it.path) }
                .thenBy { normalizedPathSegments(it.path).size }
        )
        ?: endpointsWithSpecs.first()
}

private fun commonPathPrefixSegments(leftPath: String, rightPath: String): Int {
    val leftSegments = normalizedPathSegments(leftPath)
    val rightSegments = normalizedPathSegments(rightPath)

    return leftSegments.zip(rightSegments).takeWhile { (left, right) -> left == right }.count()
}

private fun normalizedPathSegments(path: String): List<String> {
    return path.trim('/').split('/').filter { it.isNotBlank() }
}

internal fun <T, U> List<T>.zipWithPrevious(block: (previous: T?, current: T) -> U): List<U> {
    return mapIndexed { index, current -> block(getOrNull(index - 1), current) }
}
