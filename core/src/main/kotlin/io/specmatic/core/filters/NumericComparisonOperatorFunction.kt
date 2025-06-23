package io.specmatic.core.filters

import com.ezylang.evalex.Expression
import com.ezylang.evalex.data.EvaluationValue
import com.ezylang.evalex.functions.AbstractFunction
import com.ezylang.evalex.functions.FunctionParameter
import com.ezylang.evalex.parser.Token

@FunctionParameter(name = "key")
@FunctionParameter(name = "operator")
@FunctionParameter(name = "value")
class NumericComparisonOperatorFunction : AbstractFunction() {
    override fun evaluate(
        expression: Expression, functionToken: Token, vararg parameterValues: EvaluationValue
    ): EvaluationValue {

        val context = expression.dataAccessor.getData("context").value as FilterContext


        val (filterKey, operator, filterValue) = Triple(
            parameterValues[0].stringValue,
            parameterValues[1].stringValue,
            parameterValues[2].stringValue
        )

        val result = context.compare(filterKey, operator, filterValue)
        return EvaluationValue.booleanValue(result)
    }

}
