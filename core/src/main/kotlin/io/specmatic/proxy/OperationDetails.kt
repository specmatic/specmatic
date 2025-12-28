package io.specmatic.proxy

data class OperationDetails(
    val method: String,
    val path: String,
    val status: Int,
    val requestContentType: String? = null,
    val responseContentType: String? = null,
)
