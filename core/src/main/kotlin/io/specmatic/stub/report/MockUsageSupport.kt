package io.specmatic.stub.report

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord

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
