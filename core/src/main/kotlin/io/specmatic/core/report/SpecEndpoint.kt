package io.specmatic.core.report

import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.model.OpenAPIOperation

interface SpecEndpoint {
    val specification: String?
    fun toOpenApiOperation(): OpenAPIOperation
    fun toCtrfSpecConfig(): CtrfSpecConfig
}
