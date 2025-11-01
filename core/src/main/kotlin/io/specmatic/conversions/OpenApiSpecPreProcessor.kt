package io.specmatic.conversions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.conversions.JsonNodeProcessor.Companion.default
import io.specmatic.conversions.JsonNodeProcessor.Companion.jsonNodeProcessYamlMapper
import io.specmatic.core.BreadCrumb
import io.specmatic.core.log.logger

interface JsonNodeProcessor {
    fun tryProcess(jsonPath: BreadCrumb, node: JsonNode)

    fun qualifiesForProcessing(jsonPath: BreadCrumb, node: JsonNode): Boolean

    data object RemoveInvalidAdditionalProperties : JsonNodeProcessor {
        private const val ADDITIONAL_PROPERTIES = "additionalProperties"
        private const val TYPE = "type"

        override fun qualifiesForProcessing(jsonPath: BreadCrumb, node: JsonNode): Boolean {
            return !jsonPath.value.contains("example")
        }

        override fun tryProcess(jsonPath: BreadCrumb, node: JsonNode) {
            if (node !is ObjectNode) return
            if (!node.has(ADDITIONAL_PROPERTIES)) return

            val typeNode = node.get(TYPE) ?: return
            if (typeNode.isTextual && typeNode.asText().equals("object", ignoreCase = true)) return

            val typeDescription = when {
                typeNode.isTextual -> typeNode.asText()
                typeNode.isNull -> "null"
                else -> typeNode.toString()
            }

            logger.debug("Ignoring '$ADDITIONAL_PROPERTIES' from $jsonPath")
            logger.debug("$ADDITIONAL_PROPERTIES only applies to 'type: object', but found 'type: $typeDescription' at $jsonPath")
            node.remove(ADDITIONAL_PROPERTIES)
        }
    }

    data object EscapeLinkRequestBody : JsonNodeProcessor {
        private const val REQUEST_BODY = "requestBody"
        private const val LINKS = "links"

        override fun qualifiesForProcessing(jsonPath: BreadCrumb, node: JsonNode): Boolean {
            return !jsonPath.value.contains("example")
        }

        override fun tryProcess(jsonPath: BreadCrumb, node: JsonNode) {
            if (node !is ObjectNode) return
            if (!node.has(REQUEST_BODY)) return
            if (!jsonPath.previous().last().equalTo(LINKS)) return

            val requestBodyNode = node.get(REQUEST_BODY)
            if (requestBodyNode.isTextual) return

            logger.debug("Escaping '$REQUEST_BODY' at $jsonPath")
            val escapedText = jsonNodeProcessYamlMapper.writeValueAsString(requestBodyNode)
            node.put(REQUEST_BODY, escapedText)
        }
    }

    companion object {
        private val yamlFactory = YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
        val jsonNodeProcessYamlMapper: ObjectMapper = ObjectMapper(yamlFactory).registerKotlinModule()
        val default: List<JsonNodeProcessor> = buildList {
            add(RemoveInvalidAdditionalProperties)
            add(EscapeLinkRequestBody)
        }
    }
}

data class OpenApiSpecPreProcessor(private val processors: List<JsonNodeProcessor> = default) {
    fun process(rawSpec: String): String {
        if (rawSpec.isBlank()) return rawSpec
        return runCatching {
            val root = jsonNodeProcessYamlMapper.readTree(rawSpec) ?: return rawSpec
            processNode(root, BreadCrumb(""), processors)
            return jsonNodeProcessYamlMapper.writeValueAsString(root)
        }.getOrElse { e ->
            logger.debug(e, "Failed to pre-process OpenApi Specification")
            rawSpec
        }
    }

    private fun processNode(node: JsonNode, path: BreadCrumb, processors: List<JsonNodeProcessor>) {
        val filteredProcessors = processors.filter { processor ->
            if (!processor.qualifiesForProcessing(path, node)) return@filter false
            runCatching { processor.tryProcess(path, node) }.map { true }.getOrElse { e ->
                logger.log(e, "JsonNodeProcessor '${processor.javaClass.simpleName}' failed")
                false
            }
        }

        if (filteredProcessors.isEmpty()) return
        when (node) {
            is ObjectNode -> node.fieldNames().forEach { fieldName ->
                val childPath = path.plus(fieldName)
                val child = node.get(fieldName)
                processNode(child, childPath, filteredProcessors)
            }
            is ArrayNode -> node.forEachIndexed { index, element ->
                val elemPath = path.plus(index.toString())
                processNode(element, elemPath, filteredProcessors)
            }
        }
    }
}
