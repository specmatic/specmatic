package io.specmatic.core.examples.server

import CustomJsonNodeFactory
import CustomParserFactory
import com.fasterxml.jackson.core.JsonLocation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider
import java.io.File

fun findLineNumber(filePath: File, jsonPath: JsonPath): Int? {
    val customParserFactory: CustomParserFactory = CustomParserFactory()
    val om: ObjectMapper = ObjectMapper(customParserFactory)
    val factory = CustomJsonNodeFactory(
        om.getDeserializationConfig().getNodeFactory(),
        customParserFactory
    )
    om.setConfig(om.getDeserializationConfig().with(factory))
    val config = Configuration.builder()
        .mappingProvider(JacksonMappingProvider(om))
        .jsonProvider(JacksonJsonNodeJsonProvider(om))
        .options(Option.ALWAYS_RETURN_LIST)
        .build()

    val parsedDocument: DocumentContext = JsonPath.parse(filePath, config)
    val findings: ArrayNode = parsedDocument.read(jsonPath)

    val lineNumbers = findings.map { finding ->
        val location: JsonLocation = factory.getLocationForNode(finding) ?: return@map null
        location.getLineNr()
    }

    return lineNumbers.filterNotNull().firstOrNull()
}
