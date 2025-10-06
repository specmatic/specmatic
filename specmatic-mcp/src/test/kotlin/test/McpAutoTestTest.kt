package test

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.mcp.test.McpAutoTest
import io.specmatic.mcp.test.McpTransport
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

class McpAutoTestTest {

    @Test
    @Disabled("This is a utility test")
    fun `should run the auto test`() {
        val autoTest = McpAutoTest(
            baseUrl = "https://huggingface.co/mcp",
            transport = McpTransport.STREAMABLE_HTTP,
            enableResiliency = false,
            dictionaryFile = File("src/test/resources/hugging_face_dicts/simple_dict.json"),
        )
        runBlocking {
            autoTest.run()
        }
    }

    @Test
    fun `should return patterns from a schema containing $defs`() {
        val schema = ObjectMapper().readValue(
            File("src/test/resources/schema/schema_with_defs.json"),
            object : TypeReference<Map<String, Any>>() {}
        )
        val patterns = OpenApiSpecification.patternsFrom(schema, "Schema")
        assertThat(patterns.keys).containsExactlyInAnyOrder("(Product)", "(Schema)")
        assertThat(patterns["(Product)"]).isInstanceOf(JSONObjectPattern::class.java)
    }
}
