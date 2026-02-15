package io.specmatic.stub

import io.specmatic.core.HttpRequest

interface RequestInterceptor {
    val name: String

    fun interceptRequest(httpRequest: HttpRequest): HttpRequest?

    fun interceptRequestAndReturnErrors(httpRequest: HttpRequest): InterceptorResult<HttpRequest> {
        val result = interceptRequest(httpRequest)
        return if (result != null) {
            InterceptorResult.success(name, httpRequest,result)
        } else {
            InterceptorResult.passthrough(name)
        }
    }
}