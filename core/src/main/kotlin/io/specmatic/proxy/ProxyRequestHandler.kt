package io.specmatic.proxy

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse

fun interface ProxyRequestHandler {
    fun onRequest(request: HttpRequest): HttpResponse?
}
