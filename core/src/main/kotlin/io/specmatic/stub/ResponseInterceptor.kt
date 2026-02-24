package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse

interface ResponseInterceptor {
    val name: String

    fun interceptResponse(httpRequest: HttpRequest, httpResponse: HttpResponse): HttpResponse?

    fun interceptResponseAndReturnErrors(httpRequest: HttpRequest, httpResponse: HttpResponse): InterceptorResult<HttpResponse> {
        val result = interceptResponse(httpRequest, httpResponse)
        return if (result != null) {
            InterceptorResult.success(name, httpResponse,result)
        } else {
            InterceptorResult.passthrough(name)
        }
    }
}