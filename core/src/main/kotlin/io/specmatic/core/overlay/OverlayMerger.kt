package io.specmatic.core.overlay

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.jsonObjectMapper

class OverlayMerger {

    fun merge(
        baseContent: String,
        overlay: Overlay
    ): String {
        try {
            val yamlMapper = ObjectMapper(
                YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            )
            val rootNode = yamlMapper.readTree(baseContent) as ObjectNode
            val documentContext = documentContextFor(rootNode)

            fun attemptElsePrintError(jsonPath: String, operationName: String, fn: () -> Unit) {
                try {
                    fn()
                } catch (e: Exception) {
                    logger.debug("${operationName.replaceFirstChar { it.uppercaseChar() }} operation failed at JSON at path $jsonPath with error: ${e.message}")
                }
            }

            overlay.updateMap.forEach { (jsonPath, contentList) ->
                val targetNodes = documentContext.read<List<Any>>(jsonPath)
                val existingNode = if (targetNodes.isNotEmpty()) targetNodes[0] else null

                contentList.forEach { content ->
                    when (existingNode) {
                        is Map<*, *> -> {
                            val mergedContent = (existingNode as Map<String, Any?>).toMutableMap()
                            (content as Map<String, Any?>).forEach { (key, value) ->
                                mergedContent[key] = value
                            }
                            attemptElsePrintError(jsonPath, "merge") {
                                documentContext.set(jsonPath, mergedContent)
                            }
                        }

                        is List<*> -> attemptElsePrintError(jsonPath, "append") {
                            documentContext.add(jsonPath, content)
                        }

                        else -> attemptElsePrintError(jsonPath, "update") {
                            documentContext.set(jsonPath, content)
                        }
                    }
                }
            }

            overlay.removalMap.filter { it.value }.forEach { (jsonPath, _) ->
                attemptElsePrintError(jsonPath, "delete") {
                    documentContext.delete(jsonPath)
                }
            }

            return yamlMapper.writeValueAsString(
                jsonObjectMapper.readTree(documentContext.jsonString()) as ObjectNode
            ) ?: baseContent
        } catch(e: Exception) {
            println("Failed while applying overlay over the specification content with error: ${e.message}")
            return baseContent
        }
    }

    private fun documentContextFor(rootNode: ObjectNode): DocumentContext {
        return JsonPath.using(
            Configuration.builder().options(Option.ALWAYS_RETURN_LIST).build()
        ).parse(
            jsonObjectMapper.writeValueAsString(rootNode)
        )
    }
}
