package io.specmatic.core.filters

import com.ezylang.evalex.Expression
import io.specmatic.core.filters.HTTPFilterKeys.*

data class ScenarioMetadata(
    val method: String,
    val path: String,
    val statusCode: Int,
    val header: Set<String>,
    val query: Set<String>,
    val exampleName: String
) : ExpressionContextPopulator {
    override fun populateExpressionData(expression: Expression): Expression {
        return expression
            .with(METHOD.key, method)
            .with(PATH.key, path)
            .with(STATUS.key, statusCode.toString())
            .with("HEADERS", header.joinToString(","))
            .with("QUERY", query.joinToString(","))
            .with(EXAMPLE_NAME.key, exampleName)
    }
}
