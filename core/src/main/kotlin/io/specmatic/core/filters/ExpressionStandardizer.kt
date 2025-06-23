package io.specmatic.core.filters

import com.ezylang.evalex.Expression
import com.ezylang.evalex.config.ExpressionConfiguration
import java.util.Map.entry

const val ENHANCED_FUNC_NAME = "eFunc"
const val INCLUDES_FUNC_NAME = "includes"


sealed class Token {
    data class Operation(val key: String, val operator: Operator, val value: String) : Token()
    data class Symbol(val value: String) : Token()
    object And : Token()
    object Or : Token()
    object Not : Token()
    object LParen : Token()
    object RParen : Token()
}


class ExpressionStandardizer {

    fun tokenizeExpression(expression: String): String {
        val tokens = tokenize(expression)
        return tokensToString(tokens)
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0

        fun skipWhitespace() {
            while (i < expr.length && expr[i].isWhitespace()) i++
        }

        while (i < expr.length) {
            skipWhitespace()

            when {
                expr.startsWith("&&", i) -> {
                    tokens.add(Token.And)
                    i += 2
                }

                expr.startsWith("||", i) -> {
                    tokens.add(Token.Or)
                    i += 2
                }

                expr[i] == '!' -> {
                    tokens.add(Token.Not)
                    i++
                }

                expr[i] == '(' -> {
                    tokens.add(Token.LParen)
                    i++
                }

                expr[i] == ')' -> {
                    tokens.add(Token.RParen)
                    i++
                }

                else -> {
                    // Parse a key[=|!=]'value' pair
                    val keyStart = i
                    while (i < expr.length && (expr[i].isLetterOrDigit() || expr[i] == '_' || expr[i] == '.' || expr[i] == '-')) i++
                    val key = expr.substring(keyStart, i)

                    skipWhitespace()

                    val operator = Operator.ALL.find { eachOperator -> expr.startsWith(eachOperator.symbol, i) }
                        ?: throw IllegalArgumentException("Expected an operator for the given expression: $expr at position $i")

                    i += operator.symbol.length

                    skipWhitespace()

                    if (expr[i] != '\'') throw IllegalArgumentException("Expected quote for the given expression: $expr at position $i")

                    i++ // skip opening quote
                    val valueStart = i
                    while (i < expr.length && expr[i] != '\'') i++
                    val value = expr.substring(valueStart, i)
                    i++ // skip closing quote

                    tokens.add(Token.Operation(key, operator, value))
                }
            }
        }

        return tokens
    }

    private fun tokensToString(tokens: List<Token>): String {
        return tokens.joinToString(" ") { token ->
            when (token) {
                is Token.Operation -> token.operator.operate(token.key, token.value)
                is Token.And -> "&&"
                is Token.Or -> "||"
                is Token.Not -> "!"
                is Token.LParen -> "("
                is Token.RParen -> ")"
                is Token.Symbol -> token.value
            }
        }
    }

    companion object {
        fun filterToEvalEx(filterExpression: String): Expression {
            if (filterExpression.isBlank()) {
                return Expression("true", expressionConfiguration())
            }
            val expressionStandardizer = ExpressionStandardizer()
            val evalExExpression = expressionStandardizer.tokenizeExpression(filterExpression)

            return Expression(evalExExpression, expressionConfiguration())
        }

        private fun expressionConfiguration(): ExpressionConfiguration? {
            val functions = mapOf(
                ENHANCED_FUNC_NAME to NumericComparisonOperatorFunction(),
                INCLUDES_FUNC_NAME to IncludesFunction()
            )

            val configuration = ExpressionConfiguration.builder()
                .binaryAllowed(true)
                .singleQuoteStringLiteralsAllowed(true)
                .build()
                .withAdditionalFunctions(*functions.map { entry(it.key, it.value) }.toTypedArray())
            return configuration
        }
    }
}
