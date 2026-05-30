package io.specmatic.conversions

import io.specmatic.core.testBackwardCompatibility
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiXmlOneOfBackwardCompatibilityTest {
    @Test
    fun `adding an xml oneOf request alternative is backward compatible`() {
        val oldFeature = OpenApiSpecification.fromYAML(
            xmlItemsSpec(listOf(DOCUMENT)),
            ""
        ).toFeature()
        val newFeature = OpenApiSpecification.fromYAML(
            xmlItemsSpec(listOf(DOCUMENT, PARCEL)),
            ""
        ).toFeature()

        val result = testBackwardCompatibility(oldFeature, newFeature)

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
    }

    @Test
    fun `removing an xml oneOf request alternative is backward incompatible`() {
        val oldFeature = OpenApiSpecification.fromYAML(
            xmlItemsSpec(listOf(DOCUMENT, PARCEL)),
            ""
        ).toFeature()
        val newFeature = OpenApiSpecification.fromYAML(
            xmlItemsSpec(listOf(DOCUMENT)),
            ""
        ).toFeature()

        val result = testBackwardCompatibility(oldFeature, newFeature)

        assertThat(result.success()).isFalse()
        assertThat(result.report()).contains("parcel")
    }

    private fun xmlItemsSpec(variants: List<XmlVariant>): String = """
        openapi: 3.0.3
        info:
          title: XML oneOf API
          version: '1.0'
        paths:
          /items:
            post:
              requestBody:
                required: true
                content:
                  application/xml:
${variants.toSchemaYaml(20)}
${variants.toExamplesYaml(20)}
              responses:
                '200':
                  description: OK
                  content:
                    application/xml:
${listOf(DOCUMENT).toSchemaYaml(22)}
${variants.toResponseExamplesYaml(22)}
        components:
          schemas:
${variants.toComponentsYaml(12)}
    """.trimIndent()

    private fun List<XmlVariant>.toSchemaYaml(indent: Int): String {
        val indentation = " ".repeat(indent)
        val nestedIndentation = " ".repeat(indent + 2)
        val refIndentation = " ".repeat(indent + 4)
        return if (size == 1) {
            val variant = single()
            buildString {
                appendLine("${indentation}schema:")
                append(nestedIndentation)
                append("${"$"}ref: '#/components/schemas/")
                append(variant.componentName)
                append("'")
            }
        } else {
            buildString {
                appendLine("${indentation}schema:")
                appendLine("${nestedIndentation}oneOf:")
                this@toSchemaYaml.forEach { variant ->
                    append(refIndentation)
                    append("- ${"$"}ref: '#/components/schemas/")
                    append(variant.componentName)
                    appendLine("'")
                }
            }.trimEnd()
        }
    }

    private fun List<XmlVariant>.toExamplesYaml(indent: Int): String {
        val indentation = " ".repeat(indent)
        val nestedIndentation = " ".repeat(indent + 2)
        val valueIndentation = " ".repeat(indent + 4)
        return buildString {
            appendLine("${indentation}examples:")
            this@toExamplesYaml.forEach { variant ->
                appendLine("$nestedIndentation${variant.exampleName}:")
                appendLine("${valueIndentation}value: |")
                appendLine("$valueIndentation  ${variant.exampleXml}")
            }
        }.trimEnd()
    }

    private fun List<XmlVariant>.toResponseExamplesYaml(indent: Int): String {
        val indentation = " ".repeat(indent)
        val nestedIndentation = " ".repeat(indent + 2)
        val valueIndentation = " ".repeat(indent + 4)
        return buildString {
            appendLine("${indentation}examples:")
            this@toResponseExamplesYaml.forEach { variant ->
                appendLine("$nestedIndentation${variant.exampleName}:")
                appendLine("${valueIndentation}value: |")
                appendLine("$valueIndentation  ${DOCUMENT.exampleXml}")
            }
        }.trimEnd()
    }

    private fun List<XmlVariant>.toComponentsYaml(indent: Int): String {
        val indentation = " ".repeat(indent)
        val nestedIndentation = " ".repeat(indent + 2)
        val propertyIndentation = " ".repeat(indent + 4)
        return joinToString("\n") { variant ->
            buildString {
                appendLine("${indentation}${variant.componentName}:")
                appendLine("${nestedIndentation}type: object")
                appendLine("${nestedIndentation}xml:")
                appendLine("${propertyIndentation}name: ${variant.rootName}")
                appendLine("${nestedIndentation}required:")
                appendLine("${propertyIndentation}- ${variant.propertyName}")
                appendLine("${nestedIndentation}properties:")
                appendLine("${propertyIndentation}${variant.propertyName}:")
                append("${" ".repeat(indent + 6)}type: ${variant.propertyType}")
            }
        }
    }

    private data class XmlVariant(
        val componentName: String,
        val rootName: String,
        val propertyName: String,
        val propertyType: String,
        val exampleName: String,
        val exampleXml: String
    )

    private companion object {
        val DOCUMENT = XmlVariant(
            componentName = "document",
            rootName = "document",
            propertyName = "id",
            propertyType = "integer",
            exampleName = "DOCUMENT",
            exampleXml = "<document><id>10</id></document>"
        )
        val PARCEL = XmlVariant(
            componentName = "parcel",
            rootName = "parcel",
            propertyName = "trackingNumber",
            propertyType = "string",
            exampleName = "PARCEL",
            exampleXml = "<parcel><trackingNumber>ABC123</trackingNumber></parcel>"
        )
    }
}
