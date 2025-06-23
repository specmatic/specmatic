package io.specmatic.core.filters

import com.ezylang.evalex.Expression
import io.specmatic.core.Scenario

interface ExpressionContextPopulator {
    fun populateExpressionData(expression: Expression): Expression
}

class ScenarioFilterVariablePopulator(private val scenario: Scenario) : ExpressionContextPopulator {
    override fun populateExpressionData(expression: Expression): Expression {
        return expression.with("context", HttpFilterContext(scenario))
    }
}
