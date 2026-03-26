package io.specmatic.core.filters

import com.ezylang.evalex.Expression
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.test.TestRuleViolations

data class ScenarioMetadataFilter(
    val expression: Expression? = null
) {
    fun isSatisfiedBy(scenarioMetaData: ExpressionContextPopulator): Boolean {
        val expression = expression ?: return true

        val expressionWithVariables = scenarioMetaData.populateExpressionData(expression)

        return try {
            expressionWithVariables.evaluate().booleanValue ?: false
        } catch (e: Exception) {
            val errorMsg = "Error in filter expression: ${e.message?.replace("brace", "bracket")}\n"
            print(errorMsg)
            throw IllegalArgumentException(errorMsg)
        }
    }


    companion object {

        fun from(filterExpression: String): ScenarioMetadataFilter {
            if (filterExpression.isBlank()) return ScenarioMetadataFilter()
            val finalExpression = ExpressionStandardizer.filterToEvalEx(filterExpression)
            return ScenarioMetadataFilter(finalExpression)
        }

        fun <T : HasScenarioMetadata> filterUsing(
            items: Sequence<T>,
            scenarioMetadataFilter: ScenarioMetadataFilter
        ): Sequence<T> {
            return items.filter { item ->
                scenarioMetadataFilter.isSatisfiedBy(item.toScenarioMetadata())
            }
        }

        fun <T : HasScenarioMetadata, U> filterUsingDecisions(
            items: Sequence<Decision<T, U>>,
            scenarioMetadataFilter: ScenarioMetadataFilter
        ): Sequence<Decision<T, U>> {
            return items.map { item ->
                if (item !is Decision.Execute) return@map item
                if (scenarioMetadataFilter.isSatisfiedBy(item.value.toScenarioMetadata())) return@map item
                Decision.Skip(item.context, Reasoning(TestRuleViolations.EXCLUDED))
            }
        }
    }
}
