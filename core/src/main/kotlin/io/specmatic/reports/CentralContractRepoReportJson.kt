package io.specmatic.reports

import kotlinx.serialization.Serializable

@Serializable
data class CentralContractRepoReportJson(
    val specifications: List<SpecificationRow>
)

@Serializable
data class SpecificationRow(
    val specification: String,
    val serviceType: String?,
    val specType: String?,
    val operations: List<SpecificationOperation>
)

@Serializable
sealed interface SpecificationOperation

@Serializable
data class OpenAPISpecificationOperation(
    val path: String,
    val method: String,
    val responseCode: Int
): SpecificationOperation

@Serializable
data class AsyncAPISpecificationOperation(
    val operation: String,
    val channel: String,
    val action: String
) : SpecificationOperation