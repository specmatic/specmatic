package test

import io.specmatic.mcp.test.McpAutoTest
import io.specmatic.mcp.test.McpTransport
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class McpAutoTestTest {

    @Test
    fun `should run the auto test`() {
        val autoTest = McpAutoTest(
            baseUrl = "https://huggingface.co",
            transport = McpTransport.STREAMABLE_HTTP,
            enableResiliency = true,
            dictionaryFile = File("src/test/resources/hugging_face_dicts/simple_dict.json"),
            filterTools = setOf("dataset_details")
        )
        runBlocking {
            autoTest.run()
        }
    }
}
