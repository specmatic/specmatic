package io.specmatic.mcp.parser

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Test


class JsonSchemaToPatternTest {

    @Test
    fun `should return pattern from schema`() {
        val schemaFile = File("src/test/resources/full-json-schema.json")
        val schema = ObjectMapper().readTree(schemaFile)
        val pattern = JsonSchemaToPattern(schema).pattern()
        println(pattern)
    }
}
