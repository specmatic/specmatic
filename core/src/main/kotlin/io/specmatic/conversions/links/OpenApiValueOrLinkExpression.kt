package io.specmatic.conversions.links

import io.specmatic.core.pattern.HasException
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.utilities.toValue
import io.specmatic.core.utilities.yamlMapper
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.test.ExampleProcessor
import kotlin.text.replace

data class OpenApiValueOrLinkExpression(val value: Value) {
    fun toStringLiteral(): String = value.toStringLiteral()

    fun escapedIfString(): Value = when (value) {
        !is StringValue -> value
        else -> value.copy(string = value.string.replace("~", "~0").replace("/", "~1"))
    }

    companion object {
        private val embeddedExpressionRegex: Regex = Regex("""\{\$(.*?)}""")

        fun from(rawValue: Any, linkName: String): ReturnValue<OpenApiValueOrLinkExpression> {
            // TODO: Temporary Until Swagger-Parser Can Parse RequestBodies as Any
            val parsedValue = if (rawValue is String) {
                yamlMapper.readValue(rawValue, Any::class.java)
            } else {
                rawValue
            }

            return HasValue(
                OpenApiValueOrLinkExpression(
                    value = runCatching {
                        toValue(parsedValue, onStringValue = { replaceExpressionPatterns(it, linkName) })
                    }.getOrElse {
                        return HasException(it)
                    },
                ),
            )
        }

        fun replaceExpressionPatterns(input: String, linkName: String): String {
            if (input.isExpression()) return ExampleProcessor.ifNotExitsToLookupPattern("$linkName.${input.stripExpression()}").toStringLiteral()
            return input.replace(embeddedExpressionRegex) { "$($linkName.${it.groups[1]?.value})" }
        }

        private fun String.withoutBraces() = this.removeSurrounding("{", "}")

        private fun String.isExpression() = this.withoutBraces().startsWith("$")

        private fun String.stripExpression() = this.withoutBraces().removePrefix("$")
    }
}
