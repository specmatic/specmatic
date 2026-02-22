package io.specmatic.core.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigTemplateUtilsTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `findVariableTokens should return empty list when no template tokens exist`() {
        val templateText = "http://localhost:9000/orders"
        val tokens = ConfigTemplateUtils.findVariableTokens(templateText)
        assertThat(tokens).isEmpty()
    }

    @Test
    fun `findVariableTokens should parse dollar-prefixed token`() {
        val templateText = "http://localhost:\${PORT:9000}/orders"

        val tokens = ConfigTemplateUtils.findVariableTokens(templateText)
        assertThat(tokens).hasSize(1)

        val token = tokens.single()
        assertThat(token.names).containsExactly("PORT")
        assertThat(token.default).isEqualTo("9000")
        assertThat(token.fullText).isEqualTo("\${PORT:9000}")
        assertThat(templateText.substring(token.startIndex, token.endIndex + 1)).isEqualTo(token.fullText)
    }

    @Test
    fun `findVariableTokens should parse brace-only token`() {
        val templateText = "http://localhost:{PORT:9000}/orders"

        val tokens = ConfigTemplateUtils.findVariableTokens(templateText)
        assertThat(tokens).hasSize(1)

        val token = tokens.single()
        assertThat(token.names).containsExactly("PORT")
        assertThat(token.default).isEqualTo("9000")
        assertThat(token.fullText).isEqualTo("{PORT:9000}")
        assertThat(templateText.substring(token.startIndex, token.endIndex + 1)).isEqualTo(token.fullText)
    }

    @Test
    fun `findVariableTokens should parse multi-variable tokens with default`() {
        val withDefault = "jdbc:postgresql://\${DB_HOST|HOST:localhost}:5432/db"
        val tokenWithDefault = ConfigTemplateUtils.findVariableTokens(withDefault).single()
        assertThat(tokenWithDefault.names).containsExactly("DB_HOST", "HOST")
        assertThat(tokenWithDefault.default).isEqualTo("localhost")
    }

    @Test
    fun `findVariableTokens should parse multiple tokens in order`() {
        val templateText = "reports-\${name:daily}-\${formats:[html,ctrf]}-\${targetUrl:http://localhost:9000/a?x=1}"
        val tokens = ConfigTemplateUtils.findVariableTokens(templateText)
        assertThat(tokens).hasSize(3)
        assertThat(tokens.map { it.names }).containsExactly(setOf("name"), setOf("formats"), setOf("targetUrl"))
        assertThat(tokens.map { it.default }).containsExactly("daily", "[html,ctrf]", "http://localhost:9000/a?x=1")
    }

    @Test
    fun `findVariableTokens should ignore no-fallback tokens`() {
        val templateText = "jdbc:postgresql://\${DB_HOST|HOST}:5432/db and {PORT}"
        val tokens = ConfigTemplateUtils.findVariableTokens(templateText)
        assertThat(tokens).isEmpty()
    }

    @Test
    fun `findVariableTokens should ignore malformed empty-key tokens`() {
        val templateText = "prefix-{:default}-suffix and \${:default}"
        val tokens = ConfigTemplateUtils.findVariableTokens(templateText)
        assertThat(tokens).isEmpty()
    }

    @Test
    fun `findVariableTokens should discard blank names and de-duplicate repeated names`() {
        val templateText = "\${DB_HOST||HOST|DB_HOST:localhost}"
        val token = ConfigTemplateUtils.findVariableTokens(templateText).single()
        assertThat(token.names).containsExactly("DB_HOST", "HOST")
        assertThat(token.default).isEqualTo("localhost")
    }

    @Test
    fun `findVariableTokens should retain complex default containing colon and pipe`() {
        val templateText = "\${pattern:http://a|b:c}"
        val token = ConfigTemplateUtils.findVariableTokens(templateText).single()
        assertThat(token.names).containsExactly("pattern")
        assertThat(token.default).isEqualTo("http://a|b:c")
        assertThat(token.fullText).isEqualTo("\${pattern:http://a|b:c}")
    }

    @Test
    fun `findVariableTokens should return correct indices for multiple tokens`() {
        val templateText = "prefix-\${A:1}-mid-{B:2}-suffix"
        val tokens = ConfigTemplateUtils.findVariableTokens(templateText)
        assertThat(tokens).hasSize(2)
        assertThat(templateText.substring(tokens[0].startIndex, tokens[0].endIndex + 1)).isEqualTo("\${A:1}")
        assertThat(templateText.substring(tokens[1].startIndex, tokens[1].endIndex + 1)).isEqualTo("{B:2}")
    }

    @Test
    fun `createTemplate should create canonical dollar-brace token`() {
        val template = ConfigTemplateUtils.createTemplate(names = setOf("PORT"), default = "9000")
        assertThat(template).isEqualTo("\${PORT:9000}")
    }

    @Test
    fun `createTemplate should include all variable names separated by pipe`() {
        val template = ConfigTemplateUtils.createTemplate(names = linkedSetOf("DB_HOST", "HOST"), default = "localhost")
        assertThat(template).isEqualTo("\${DB_HOST|HOST:localhost}")
    }

    @Test
    fun `isConfigTemplate should match any string containing a template token`() {
        assertThat(ConfigTemplateUtils.isConfigTemplate("{SYSTEM_PROP:default-value}")).isTrue()
        assertThat(ConfigTemplateUtils.isConfigTemplate("\${SYSTEM_PROP:default-value}")).isTrue()
        assertThat(ConfigTemplateUtils.isConfigTemplate("prefix-{SYSTEM_PROP:default}-suffix")).isTrue()
        assertThat(ConfigTemplateUtils.isConfigTemplate("{SYSTEM_PROP}")).isFalse()
    }
    
    @Test
    fun `isFullTemplateToken should match only complete template tokens`() {
        assertThat(ConfigTemplateUtils.isFullTemplateToken("{SYSTEM_PROP:default-value}")).isTrue()
        assertThat(ConfigTemplateUtils.isFullTemplateToken("\${SYSTEM_PROP:default-value}")).isTrue()
        assertThat(ConfigTemplateUtils.isFullTemplateToken("prefix-{SYSTEM_PROP:default}-suffix")).isFalse()
        assertThat(ConfigTemplateUtils.isFullTemplateToken("{SYSTEM_PROP}")).isFalse()
    }

    @Test
    fun `resolveConfigTemplateValue should resolve embedded and full templates`() {
        assertThat(ConfigTemplateUtils.resolveTemplateValue("{SYSTEM_PROP:default-value}")).isEqualTo("default-value")
        assertThat(ConfigTemplateUtils.resolveTemplateValue("prefix-{SYSTEM_PROP:default}-suffix")).isEqualTo("prefix-default-suffix")
        assertThat(ConfigTemplateUtils.resolveTemplateValue("{SYSTEM_PROP}")).isEqualTo("{SYSTEM_PROP}")
    }

    @Test
    fun `resolveTemplateValue string should preserve array-like resolved defaults`() {
        assertThat(ConfigTemplateUtils.resolveTemplateValue("\${report:[html, ctrf]}")).isEqualTo("""["html","ctrf"]""")
    }

    @Test
    fun `resolveTemplateValue string should preserve object-like resolved defaults`() {
        assertThat(ConfigTemplateUtils.resolveTemplateValue("""${'$'}{reportConfig:{"formats":["html","ctrf"],"publish":true}}"""))
            .isEqualTo("""{"formats":["html","ctrf"],"publish":true}""")
    }
    
    @Test
    fun `resolveTemplateValue string should preserve number defaults`() {
        assertThat(ConfigTemplateUtils.resolveTemplateValue("\${PORT:9000}")).isEqualTo("9000")
    }
    
    @Test
    fun `resolveTemplateValue string should preserve boolean defaults`() {
        assertThat(ConfigTemplateUtils.resolveTemplateValue("\${enabled:true}")).isEqualTo("true")
        assertThat(ConfigTemplateUtils.resolveTemplateValue("\${enabled:false}")).isEqualTo("false")
    }

    @Test
    fun `resolveTemplateValue JsonNode should resolve templates in object fields`() {
        val node = objectMapper.readTree("""{"license_path":"prefix-{VAR:default}-suffix","retries":3}""")
        val resolved = ConfigTemplateUtils.resolveTemplateValue(node)
        assertThat(resolved["license_path"].asText()).isEqualTo("prefix-default-suffix")
        assertThat(resolved["retries"].asInt()).isEqualTo(3)
    }

    @Test
    fun `resolveTemplateValue JsonNode should resolve templates inside arrays`() {
        val node = objectMapper.readTree("""["{A:one}","prefix-{B:two}-suffix",7]""")
        val resolved = ConfigTemplateUtils.resolveTemplateValue(node)
        assertThat(resolved[0].asText()).isEqualTo("one")
        assertThat(resolved[1].asText()).isEqualTo("prefix-two-suffix")
        assertThat(resolved[2].asInt()).isEqualTo(7)
    }
}
