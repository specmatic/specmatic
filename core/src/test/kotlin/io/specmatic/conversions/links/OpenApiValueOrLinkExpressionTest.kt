package io.specmatic.conversions.links

import io.specmatic.core.pattern.HasValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiValueOrLinkExpressionTest {
    @Test
    fun `should convert ESCAPED stringified json-objects to property objects before processing`() {
        val value = """<ESCAPED>{"name": "john", "age": 10}"""
        val openApiValueOrLinkExpression = OpenApiValueOrLinkExpression.from(value, "TEST")

        assertThat(openApiValueOrLinkExpression).isInstanceOf(HasValue::class.java)
        assertThat(openApiValueOrLinkExpression.value.value).isInstanceOf(JSONObjectValue::class.java)
    }

    @Test
    fun `should not convert NON-ESCAPED stringified json-objects to property objects before processing`() {
        val value = """{"name": "john", "age": 10}"""
        val openApiValueOrLinkExpression = OpenApiValueOrLinkExpression.from(value, "TEST")

        assertThat(openApiValueOrLinkExpression).isInstanceOf(HasValue::class.java)
        assertThat(openApiValueOrLinkExpression.value.value).isInstanceOf(StringValue::class.java)
    }

    @Test
    fun `should convert openapi link expression syntax to internal link expression syntax with link name prefix`() {
        val value = """${'$'}response.body#/data/0/product/id"""
        val openApiValueOrLinkExpression = OpenApiValueOrLinkExpression.from(value, "TEST")

        assertThat(openApiValueOrLinkExpression).isInstanceOf(HasValue::class.java)
        assertThat(openApiValueOrLinkExpression.value.value).isInstanceOf(StringValue::class.java)
        assertThat(openApiValueOrLinkExpression.value.value.toStringLiteral()).isEqualTo("$(TEST.response.body#/data/0/product/id)")
    }

    @Test
    fun `should convert embedded openapi link expression syntax to internal link expression syntax with link name prefix`() {
        val value = """NEW-PRODUCT-{${'$'}response.body#/data/0/product/name}"""
        val openApiValueOrLinkExpression = OpenApiValueOrLinkExpression.from(value, "TEST")

        assertThat(openApiValueOrLinkExpression).isInstanceOf(HasValue::class.java)
        assertThat(openApiValueOrLinkExpression.value.value).isInstanceOf(StringValue::class.java)
        assertThat(openApiValueOrLinkExpression.value.value.toStringLiteral()).isEqualTo(
            "NEW-PRODUCT-$(TEST.response.body#/data/0/product/name)",
        )
    }

    @Test
    @Suppress("ktlint:standard:multiline-expression-wrapping", "ktlint:standard:string-template-indent", "ktlint:standard:wrapping")
    fun `should convert nested openapi link expression syntax to internal link expression syntax with link name prefix`() {
        val value = """<ESCAPED>{
        "name": "NEW-PRODUCT-{${'$'}response.body#/data/0/product/name}",
        "details": {
            "age": 10,
            "price": "${'$'}response.body#/data/0/product/price",
        },
        "aliases": [
            "FirstAlias",
            "${'$'}response.body#/data/0/product/newAlias"
        ]
        }""".trimIndent()

        val openApiValueOrLinkExpression = OpenApiValueOrLinkExpression.from(value, "TEST")

        assertThat(openApiValueOrLinkExpression).isInstanceOf(HasValue::class.java)
        assertThat(openApiValueOrLinkExpression.value.value).isInstanceOf(JSONObjectValue::class.java)
        assertThat(openApiValueOrLinkExpression.value.toStringLiteral()).isEqualToIgnoringWhitespace("""{
        "name": "NEW-PRODUCT-$(TEST.response.body#/data/0/product/name)",
        "details": {
            "age": 10,
            "price": "$(TEST.response.body#/data/0/product/price)"
        },
        "aliases": [
            "FirstAlias",
            "$(TEST.response.body#/data/0/product/newAlias)"
        ]
        }""".trimIndent())
    }
}
