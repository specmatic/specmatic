package io.specmatic.core.filters

import com.ezylang.evalex.Expression
import com.ezylang.evalex.data.EvaluationValue
import com.ezylang.evalex.functions.AbstractFunction
import com.ezylang.evalex.functions.FunctionParameter
import com.ezylang.evalex.parser.Token



@FunctionParameter(name = "key")
@FunctionParameter(name = "possibleValues", isVarArg = true)
class IncludesFunction : AbstractFunction() {
    override fun evaluate(
        expression: Expression, functionToken: Token, vararg parameterValues: EvaluationValue
    ): EvaluationValue? {
        val paramName = parameterValues.first().stringValue
        val possibleValues = parameterValues.drop(1).map { it.stringValue }

        val context = expression.dataAccessor.getData("context").value as FilterContext
        val result = context.includes(paramName, possibleValues)

        return EvaluationValue.booleanValue(result)
    }

}
