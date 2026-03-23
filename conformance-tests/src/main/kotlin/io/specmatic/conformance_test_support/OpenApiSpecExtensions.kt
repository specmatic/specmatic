package io.specmatic.conformance_test_support

import io.swagger.v3.oas.models.OpenAPI

data class Operation(val method: String, val path: String)

fun OpenAPI.toOperations(): Set<Operation> =
    paths.orEmpty().flatMap { (path, item) ->
        item.readOperationsMap().map { (method, _) -> Operation(method.name, path) }
    }.toSet()
