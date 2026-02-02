package io.specmatic.core.config.v3

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.zenwave360.jsonrefparser.resolver.FileResolver
import io.zenwave360.jsonrefparser.resolver.RefFormat
import io.zenwave360.jsonrefparser.`$Ref` as Ref
import io.zenwave360.jsonrefparser.`$RefParser` as RefParser
import io.zenwave360.jsonrefparser.resolver.Resolver
import java.net.URI
import java.nio.file.Path

class SpecmaticConfigV3Resolver(components: Components, private val originFile: Path): RefOrValueResolver {
    private val mapper = jacksonObjectMapper()
    private val componentsAsMap: Map<*, *> = mapper.convertValue(components, object : TypeReference<Map<*, *>>() {})
    private val internalResolver = InternalComponentsResolver(componentsAsMap)

    override fun resolveRef(reference: String): Map<*, *> {
        if (reference.startsWith("#/components/")) return internalResolver.resolveRef(reference)
        val (rootUri, virtualJson) = buildVirtualJson(reference)
        val memoryResolver = InMemoryResolver(mapOf(rootUri to virtualJson))

        val resolver = DelegatingResolver(primary = memoryResolver, fallback = FileResolver())
        val parser = RefParser(rootUri).withResolver(RefFormat.FILE, resolver).withResolver(RefFormat.RELATIVE, resolver)
        parser.parse().dereference()

        val resolved = parser.refs.schema()["__target__"] ?: error("Unable to resolve reference: $reference")
        require(resolved is Map<*, *>) { "Resolved reference is not an object: $reference" }
        return resolved
    }

    private fun buildVirtualJson(reference: String): Pair<URI, String> {
        val rootUri = originFile.toUri()
        val virtual = mapOf("components" to componentsAsMap, "__target__" to mapOf("\$ref" to reference))
        val text = mapper.writeValueAsString(virtual)
        return rootUri to text
    }
}

private class DelegatingResolver(private val primary: Resolver, private val fallback: Resolver) : Resolver {
    override fun resolve(`$ref`: Ref?): String? {
        return primary.resolve(`$ref`) ?: fallback.resolve(`$ref`)
    }
}

private class InMemoryResolver(private val documents: Map<URI, String>) : Resolver {
    override fun resolve(`$ref`: Ref?): String? {
        if (`$ref` == null) return null
        val uri = `$ref`.uri ?: return null
        return documents[uri]
    }
}

private class InternalComponentsResolver(private val componentsAsMap: Map<*, *>) : RefOrValueResolver {
    override fun resolveRef(reference: String): Map<*, *> {
        require(reference.startsWith("#/components/")) {
            "Only internal component refs are supported here: $reference"
        }

        val path = reference.removePrefix("#/").split('/').drop(1).fold(componentsAsMap as Any?) { acc, key ->
            (acc as? Map<*, *>)?.get(key)
        }

        return when (path) {
            is Map<*, *> -> path
            null -> error("Component reference does not exist: $reference")
            else -> error("Reference $reference does not resolve to an object")
        }
    }
}
