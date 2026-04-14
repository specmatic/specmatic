package io.specmatic.stub.report

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord

internal fun StubEndpoint.toOpenApiOperation(): OpenAPIOperation {
    return OpenAPIOperation(
        path = path.orEmpty(),
        method = method.orEmpty(),
        contentType = requestContentType,
        responseCode = responseCode,
        protocol = protocol,
        responseContentType = responseContentType,
    )
}

internal fun StubEndpoint.toCtrfSpecConfig(): CtrfSpecConfig {
    return CtrfSpecConfig(
        protocol = protocol.key,
        specType = specType.value,
        specification = specification.orEmpty(),
        sourceProvider = sourceProvider,
        repository = sourceRepository,
        branch = sourceRepositoryBranch ?: "main",
    )
}

internal fun TestResultRecord.toMockUsageOperation(): OpenAPIOperation {
    return OpenAPIOperation(
        path = path,
        method = soapAction ?: method,
        contentType = requestContentType,
        responseCode = responseStatus,
        protocol = SpecmaticProtocol.HTTP,
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

internal fun TestResultRecord.attempts(operation: OpenAPIOperation): Boolean {
    return path == operation.path &&
        (soapAction ?: method).equals(operation.method, ignoreCase = true) &&
        requestContentType == operation.contentType &&
        responseStatus == operation.responseCode &&
        responseContentType == operation.responseContentType
}

internal fun TestResultRecord.matches(operation: OpenAPIOperation): Boolean {
    return path == operation.path &&
        (soapAction ?: method).equals(operation.method, ignoreCase = true) &&
        requestContentType == operation.contentType &&
        actualResponseStatus == operation.responseCode &&
        actualResponseContentType == operation.responseContentType
}

internal fun closestMatchingEndpointFor(path: String, method: String, endpoints: List<StubEndpoint>): StubEndpoint? {
    val endpointsWithSpecs = endpoints.filter { it.specification != null }
    if (endpointsWithSpecs.isEmpty()) {
        return null
    }

    val methodMatchedEndpoints = endpointsWithSpecs.filter { it.method.equals(method, ignoreCase = true) }
    val candidateEndpoints = methodMatchedEndpoints.ifEmpty { endpointsWithSpecs }

    return candidateEndpoints
        .maxWithOrNull(
            compareBy<StubEndpoint> { commonPathPrefixSegments(path, it.path.orEmpty()) }
                .thenBy { normalizedPathSegments(it.path.orEmpty()).size }
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
