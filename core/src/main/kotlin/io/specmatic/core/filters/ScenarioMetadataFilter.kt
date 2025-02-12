package io.specmatic.core.filters

import com.ezylang.evalex.Expression
import com.ezylang.evalex.config.ExpressionConfiguration
import io.specmatic.test.TestResultRecord

data class ScenarioMetadataFilter(
    val expression: Expression? = null
) {
    fun isSatisfiedBy(filterableExpression: FilterableExpression): Boolean {
        val expression = expression ?: return true

        val expressionWithVariables = filterableExpression.populateExpressionData(expression)

        return try {
            expressionWithVariables.evaluate().booleanValue ?: false
        } catch (e: Exception) {
            val errorMsg = "Error in filter expression: ${e.message?.replace("brace", "bracket")}\n"
            print(errorMsg)
            throw IllegalArgumentException(errorMsg)
        }
    }


    companion object {
        const val ENHANCED_FUNC_NAME = "eFunc"

        fun from(filterExpression: String): ScenarioMetadataFilter {
            if (filterExpression.isBlank()) return ScenarioMetadataFilter()
            val evalExExpression = standardizeExpression(filterExpression)
            val configuration = ExpressionConfiguration.builder()
                .singleQuoteStringLiteralsAllowed(true).build()
                .withAdditionalFunctions(
                    mapOf(Pair(ENHANCED_FUNC_NAME, EnhancedRHSValueEvalFunction())).entries.single()
                )
            val finalExpression = Expression(evalExExpression, configuration)
            return ScenarioMetadataFilter(expression = finalExpression)
        }

        fun standardizeExpression(expression: String): String {
            val regexPattern = "\\b\\w+(=|!=)('[^']*([,x*])[^']*')".trimIndent().toRegex()

            return regexPattern.replace(expression) { matchResult ->
                "$ENHANCED_FUNC_NAME('${matchResult.value.filter { it != '\'' }}')"
            }
        }

        fun <T> filterUsing(
            items: Sequence<T>,
            scenarioMetadataFilter: ScenarioMetadataFilter,
            toFilterableMetadata: (T) -> FilterableExpression
        ): Sequence<T> {
            val filteredItems = items.filter {
                scenarioMetadataFilter.isSatisfiedBy(toFilterableMetadata(it))
            }
            return filteredItems
        }
    }
}