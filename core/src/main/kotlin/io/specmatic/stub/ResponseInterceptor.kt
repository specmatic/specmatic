package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse

interface ResponseInterceptor {
    fun interceptResponse(httpRequest: HttpRequest, httpResponse: HttpResponse): HttpResponse?

    fun interceptResponseWithErrors(httpRequest: HttpRequest, httpResponse: HttpResponse): InterceptorResult<HttpResponse> {
        val result = interceptResponse(httpRequest, httpResponse)
        return if (result != null) {
            InterceptorResult.success(result)
        } else {
            InterceptorResult.passthrough()
        }
    }
}