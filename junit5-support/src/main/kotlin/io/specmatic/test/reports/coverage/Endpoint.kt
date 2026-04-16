package io.specmatic.test.reports.coverage

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.Scenario
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import kotlinx.serialization.Serializable

@Serializable
data class Endpoint(
    val path: String,
    val method: String,
    val responseStatus: Int,
    val soapAction: String? = null,
    val sourceProvider: String? = null,
    val sourceRepository: String? = null,
    val sourceRepositoryBranch: String? = null,
    val specification: String? = null,
    val requestContentType: String? = null,
    val responseContentType: String? = null,
    val protocol: SpecmaticProtocol,
    val specType: SpecType,
) {
    constructor(scenario: Scenario): this(
        path = convertPathParameterStyle(scenario.path),
        method = scenario.method,
        responseStatus = scenario.status,
        soapAction = scenario.soapActionUnescaped,
        sourceProvider = scenario.sourceProvider,
        sourceRepository = scenario.sourceRepository,
        sourceRepositoryBranch = scenario.sourceRepositoryBranch,
        specification = scenario.specification,
        requestContentType = scenario.requestContentType,
        responseContentType = scenario.responseContentType,
        protocol = scenario.protocol,
        specType = scenario.specType
    )
}
