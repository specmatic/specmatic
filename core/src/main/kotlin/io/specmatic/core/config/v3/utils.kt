package io.specmatic.core.config.v3

import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.isOpenAPI
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.collections.contains

internal fun determineSpecTypeFor(specFile: File): List<SpecType> {
    return when {
        specFile.extension == "wsdl" -> listOf(SpecType.WSDL)
        specFile.extension == "proto" -> listOf(SpecType.PROTOBUF)
        specFile.extension in setOf("graphql", "graphqls") -> listOf(SpecType.GRAPHQL)
        isOpenAPI(specFile.canonicalPath, logFailure = false) -> listOf(SpecType.OPENAPI)
        isAsyncAPI(specFile.canonicalPath) -> listOf(SpecType.ASYNCAPI)
        else -> return listOf(SpecType.OPENAPI, SpecType.ASYNCAPI)
    }
}

private fun isAsyncAPI(path: String): Boolean = try {
    File(path).reader().use { reader ->
        Yaml().load<MutableMap<String, Any?>>(reader).contains("asyncapi")
    }
} catch (_: Throwable) {
    false
}
