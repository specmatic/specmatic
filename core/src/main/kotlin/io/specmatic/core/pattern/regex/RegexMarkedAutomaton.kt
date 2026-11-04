package io.specmatic.core.pattern.regex

import dk.brics.automaton.Automaton
import dk.brics.automaton.RegExp

internal class RegexMarkedAutomaton(private val candidateCharacter: Automaton) {
    fun compile(expression: String): Automaton {
        val tokens = RegexScanner(bricsStrings = true).scan(expression).tokens
        val classes = tokens.filter {
            isCharacterSet(it, expression)
        }.associate { token ->
            val automaton = RegExp(token.text(expression), 0).toAutomaton()
            className(token) to automaton.intersection(candidateCharacter)
        }

        val protected = tokens.joinToString("") { token -> render(token, expression) }
        return RegExp(protected, RegExp.AUTOMATON).toAutomaton(classes)
    }

    private fun render(token: RegexToken, expression: String): String {
        if (isCharacterSet(token, expression)) return "<${className(token)}>"
        val text = token.text(expression)
        if (token is RegexToken.Character && text in listOf("<", ">")) return "\\$text"
        return text
    }

    private fun isCharacterSet(token: RegexToken, expression: String): Boolean {
        return token is RegexToken.CharacterClass || token is RegexToken.Character && token.text(expression) == "."
    }

    private fun className(token: RegexToken): String {
        return "class${token.span.start}"
    }
}
