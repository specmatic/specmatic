package io.specmatic.core.config.v3

import io.specmatic.core.OPENAPI_FILE_EXTENSIONS
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.isOpenAPI
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.collections.contains

internal fun determineSpecTypeFor(specFile: File): List<SpecType> {
    return when(specFile.extension.lowercase()) {
        "wsdl" ->
            listOf(SpecType.WSDL)
        "proto" ->
            listOf(SpecType.PROTOBUF)
        in setOf("graphql", "graphqls") ->
            listOf(SpecType.GRAPHQL)
        in OPENAPI_FILE_EXTENSIONS if isOpenAPI(specFile.canonicalPath, logFailure = false) ->
            listOf(SpecType.OPENAPI)
        in OPENAPI_FILE_EXTENSIONS if isAsyncAPI(specFile.canonicalPath) ->
            listOf(SpecType.ASYNCAPI)
        else ->
            listOf(SpecType.OPENAPI, SpecType.ASYNCAPI)
    }
}

private fun isAsyncAPI(path: String): Boolean = try {
    File(path).reader().use { reader ->
        Yaml().load<MutableMap<String, Any?>>(reader).contains("asyncapi")
    }
} catch (_: Throwable) {
    false
}
