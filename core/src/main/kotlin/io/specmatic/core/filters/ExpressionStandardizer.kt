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
        private sealed class FilterExpr {
            abstract fun projectUnsupportedKeys(isSupportedKey: (String) -> Boolean): FilterExpr?

            data class Condition(val key: String, val operator: Operator, val value: String) : FilterExpr() {
                override fun projectUnsupportedKeys(isSupportedKey: (String) -> Boolean): FilterExpr? {
                    return takeIf { isSupportedKey(key) }
                }
            }

            data class Not(val expr: FilterExpr) : FilterExpr() {
                override fun projectUnsupportedKeys(isSupportedKey: (String) -> Boolean): FilterExpr? {
                    return expr.projectUnsupportedKeys(isSupportedKey)?.let { Not(it) }
                }
            }

            data class And(val left: FilterExpr, val right: FilterExpr) : FilterExpr() {
                override fun projectUnsupportedKeys(isSupportedKey: (String) -> Boolean): FilterExpr? {
                    val projectedLeft = left.projectUnsupportedKeys(isSupportedKey)
                    val projectedRight = right.projectUnsupportedKeys(isSupportedKey)

                    return when {
                        projectedLeft == null && projectedRight == null -> null
                        projectedLeft == null -> projectedRight
                        projectedRight == null -> projectedLeft
                        else -> And(projectedLeft, projectedRight)
                    }
                }
            }

            data class Or(val left: FilterExpr, val right: FilterExpr) : FilterExpr() {
                override fun projectUnsupportedKeys(isSupportedKey: (String) -> Boolean): FilterExpr? {
                    val projectedLeft = left.projectUnsupportedKeys(isSupportedKey)
                    val projectedRight = right.projectUnsupportedKeys(isSupportedKey)

                    return when {
                        projectedLeft == null && projectedRight == null -> null
                        projectedLeft == null -> projectedRight
                        projectedRight == null -> projectedLeft
                        else -> Or(projectedLeft, projectedRight)
                    }
                }
            }
        }

        private class FilterExprParser(private val tokens: List<Token>) {
            private var index = 0

            fun parse(): FilterExpr? {
                if (tokens.isEmpty()) return null
                val expr = parseOr()
                if (index != tokens.size) {
                    throw IllegalArgumentException("Unexpected token in filter expression")
                }
                return expr
            }

            private fun parseOr(): FilterExpr {
                var expr = parseAnd()
                while (match<Token.Or>()) {
                    expr = FilterExpr.Or(expr, parseAnd())
                }
                return expr
            }

            private fun parseAnd(): FilterExpr {
                var expr = parseUnary()
                while (match<Token.And>()) {
                    expr = FilterExpr.And(expr, parseUnary())
                }
                return expr
            }

            private fun parseUnary(): FilterExpr {
                return if (match<Token.Not>()) {
                    FilterExpr.Not(parseUnary())
                } else {
                    parsePrimary()
                }
            }

            private fun parsePrimary(): FilterExpr {
                if (match<Token.LParen>()) {
                    val expr = parseOr()
                    if (!match<Token.RParen>()) {
                        throw IllegalArgumentException("Mismatched parentheses in filter expression")
                    }
                    return expr
                }

                return when (val token = tokens.getOrNull(index++)) {
                    is Token.Operation -> FilterExpr.Condition(token.key, token.operator, token.value)
                    else -> throw IllegalArgumentException("Unexpected token in filter expression")
                }
            }

            private inline fun <reified T : Token> match(): Boolean {
                if (index >= tokens.size || tokens[index] !is T) return false
                index++
                return true
            }
        }

        private fun renderToEvalEx(expr: FilterExpr?): String {
            if (expr == null) return "true"

            fun precedence(node: FilterExpr): Int = when (node) {
                is FilterExpr.Or -> 1
                is FilterExpr.And -> 2
                is FilterExpr.Not -> 3
                is FilterExpr.Condition -> 4
            }

            fun render(node: FilterExpr): String {
                return when (node) {
                    is FilterExpr.Condition -> node.operator.operate(node.key, node.value)
                    is FilterExpr.Not -> {
                        val child = render(node.expr)
                        val wrapped = if (precedence(node.expr) < precedence(node)) "( $child )" else child
                        "!$wrapped"
                    }
                    is FilterExpr.And -> {
                        val left = render(node.left)
                        val right = render(node.right)
                        val leftWrapped = if (precedence(node.left) < precedence(node)) "( $left )" else left
                        val rightWrapped = if (precedence(node.right) < precedence(node)) "( $right )" else right
                        "$leftWrapped && $rightWrapped"
                    }
                    is FilterExpr.Or -> {
                        val left = render(node.left)
                        val right = render(node.right)
                        val leftWrapped = if (precedence(node.left) < precedence(node)) "( $left )" else left
                        val rightWrapped = if (precedence(node.right) < precedence(node)) "( $right )" else right
                        "$leftWrapped || $rightWrapped"
                    }
                }
            }

            return render(expr)
        }

        fun filterToEvalEx(filterExpression: String): Expression {
            if (filterExpression.isBlank()) {
                return Expression("true", expressionConfiguration())
            }
            val expressionStandardizer = ExpressionStandardizer()
            val evalExExpression = expressionStandardizer.tokenizeExpression(filterExpression)

            return Expression(evalExExpression, expressionConfiguration())
        }

        fun filterToEvalExForSupportedKeys(
            filterExpression: String,
            isSupportedKey: (String) -> Boolean
        ): Expression {
            if (filterExpression.isBlank()) {
                return Expression("true", expressionConfiguration())
            }

            val expressionStandardizer = ExpressionStandardizer()
            val tokens = expressionStandardizer.tokenize(filterExpression)
            val parsedExpression = FilterExprParser(tokens).parse()
            val projectedExpression = parsedExpression?.projectUnsupportedKeys(isSupportedKey)
            return Expression(renderToEvalEx(projectedExpression), expressionConfiguration())
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
