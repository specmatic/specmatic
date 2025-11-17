package io.specmatic.test.reports.coverage.html

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

class HtmlReportTest {
    private val objectMapper = ObjectMapper()
    private val startTag = """<script id="json-data" type="application/json">"""
    private val endTag = "</script>"
    private val templateEngine = TemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
        })
    }

    private fun renderTemplateWithTestData(testData: Any): String {
        val context = Context().apply { setVariable("testData", testData) }
        return templateEngine.process("test-template", context)
    }

    private fun extractScriptContent(html: String): String {
        val startIndex = html.indexOf(startTag)
        val endIndex = html.indexOf(endTag, startIndex + startTag.length)
        assertThat(startIndex).isGreaterThanOrEqualTo(0)
        assertThat(endIndex).isGreaterThan(startIndex)
        return html.substring(startIndex + startTag.length, endIndex).trim()
    }

    private fun assertJsonContent(scriptContent: String, expectedPairs: Map<String, Any?>) {
        val jsonNode = objectMapper.readTree(scriptContent)
        for ((key, value) in expectedPairs) {
            val node = jsonNode.get(key)
            assertThat(node).isNotNull
            when (value) {
                is String -> assertThat(node.asText()).isEqualTo(value)
                is Int -> assertThat(node.asInt()).isEqualTo(value)
                is Boolean -> assertThat(node.asBoolean()).isEqualTo(value)
                else -> assertThat(node.toString()).isEqualTo(value.toString())
            }
        }
    }

    @Test
    fun `test simple key value pairs map serialization`() {
        val testData = mapOf("key" to "value", "number" to 42)
        val html = renderTemplateWithTestData(testData)
        val scriptContent = extractScriptContent(html)
        assertJsonContent(scriptContent, mapOf("key" to "value", "number" to 42))
    }

    @Test
    fun `test simple list serialization`() {
        val testData = mapOf("list" to listOf("one", "two", "three"))
        val html = renderTemplateWithTestData(testData)
        val scriptContent = extractScriptContent(html)

        val jsonNode = objectMapper.readTree(scriptContent)
        val listNode = jsonNode.get("list")
        assertThat(listNode).isNotNull
        assertThat(listNode.isArray).isTrue
        assertThat(listNode[0].asText()).isEqualTo("one")
    }

    @Test
    fun `test HTML special characters in strings are escaped properly`() {
        val testData = mapOf("html" to "<div>Hello & 'world' \"test\"</div>")
        val html = renderTemplateWithTestData(testData)
        val scriptContent = extractScriptContent(html)

        assertThat(scriptContent).isEqualTo("""{"html":"<div>Hello \u0026 'world' \"test\"<\/div>"}""")
        assertJsonContent(scriptContent, mapOf("html" to "<div>Hello & 'world' \"test\"</div>"))
    }
}
