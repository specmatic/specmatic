package io.specmatic.stub

import io.specmatic.core.HttpRequest

interface RequestInterceptor {
    fun interceptRequest(httpRequest: HttpRequest): HttpRequest?

    fun interceptRequestAndReturnErrors(httpRequest: HttpRequest): InterceptorResult<HttpRequest> {
        val result = interceptRequest(httpRequest)
        return if (result != null) {
            InterceptorResult.success(result)
        } else {
            InterceptorResult.passthrough()
        }
    }
}