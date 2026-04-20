package io.specmatic.test.reports.coverage

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.test.TestResultRecord
import io.specmatic.reporter.model.OpenAPIOperation

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

internal fun <T, U> List<T>.zipWithPrevious(block: (previous: T?, current: T) -> U): List<U> {
    return mapIndexed { index, current -> block(getOrNull(index - 1), current) }
}
