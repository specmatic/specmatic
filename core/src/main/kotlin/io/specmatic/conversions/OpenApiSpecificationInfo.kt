package io.specmatic.conversions

import io.swagger.v3.oas.models.OpenAPI

fun openApiSpecificationInfo(openApiFilePath: String, parsedOpenApi: OpenAPI): String {
    val info = StringBuilder()

    info.append("API Specification Summary: $openApiFilePath\n")
    info.append("  OpenAPI Version: ${parsedOpenApi.openapi}\n")

    parsedOpenApi.paths?.let {
        val operationsCount = it.map { (_, pathItem) ->
            pathItem.readOperationsMap().map { (_, operation) -> operation.operationId }
        }.flatten().toList().size
        info.append("  API Paths: ${it.size}, API Operations: $operationsCount\n")
    }

    parsedOpenApi.components?.let {
        info.append("  Schema components: ${it.schemas?.size}, Security Schemes: ${it.securitySchemes?.values?.mapNotNull { securityScheme -> securityScheme.type?.toString() } ?: "none"}\n")
    }

    return info.toString()
}