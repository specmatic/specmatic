package io.specmatic.core.config.v3.components

class Adapter(
    val preSpecmaticRequestProcessor: String? = null,
    val postSpecmaticResponseProcessor: String? = null,
    val preSpecmaticResponseProcessor: String? = null
)
