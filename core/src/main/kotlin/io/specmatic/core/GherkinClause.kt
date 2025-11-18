package io.specmatic.core

import io.specmatic.core.GherkinSection.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.Value
import io.specmatic.core.value.dictionaryToDeclarations

data class GherkinClause(val content: String, val section: GherkinSection)

enum class GherkinSection(val prefix: String) {
    Given("Given"), When("When"), Then("Then"), Star("*")
}

fun responseBodyToGherkinClauses(typeName: String, body: Value?, types: Map<String, Pattern>): Triple<List<GherkinClause>, Map<String, Pattern>, ExampleDeclarations>? {
    if(body == EmptyString)
        return Triple(emptyList(), types, DiscardExampleDeclarations())

    return body?.typeDeclarationWithKey(typeName, types, DiscardExampleDeclarations())?.let { (typeDeclaration, _) ->
        val bodyClause = GherkinClause("response-body ${typeDeclaration.typeValue}", Then)
        Triple(listOf(bodyClause), typeDeclaration.types, DiscardExampleDeclarations())
    }
}

fun requestBodyToGherkinClauses(body: Value?, types: Map<String, Pattern>, exampleDeclarationsStore: ExampleDeclarations): Triple<List<GherkinClause>, Map<String, Pattern>, ExampleDeclarations> {
    if(body == EmptyString || body == NoBodyValue)
        return Triple(emptyList(), types, exampleDeclarationsStore)

    val declarations = body?.typeDeclarationWithoutKey("RequestBody", types, exampleDeclarationsStore)?.let { (typeDeclaration, exampleDeclaration) ->
        val typeValue = exampleDeclaration.getNewName(typeDeclaration.typeValue, typeDeclaration.types.keys)

        val bodyClause = GherkinClause("request-body $typeValue", When)

        Triple(listOf(bodyClause), typeDeclaration.types, exampleDeclaration)
    }

    return declarations ?: Triple(emptyList(), types, exampleDeclarationsStore)
}

fun toGherkinClauses(patterns: Map<String, Pattern>): List<GherkinClause> {
    return patterns.entries.map { (key, pattern) -> toClause(key, pattern) }
}

fun headersToGherkin(headers: Map<String, String>, keyword: String, types: Map<String, Pattern>, exampleDeclarationsStore: ExampleDeclarations, section: GherkinSection): Triple<List<GherkinClause>, Map<String, Pattern>, ExampleDeclarations> {
    val (dictionaryTypeMap, newTypes, newExamples) = dictionaryToDeclarations(stringMapToScalarMap(headers), types, exampleDeclarationsStore)

    val headerClauses = dictionaryTypeMap.entries.map {
        "$keyword ${it.key} ${it.value.pattern}"
    }.map { GherkinClause(it, section) }

    return Triple(headerClauses, newTypes, newExamples)
}

fun toClause(key: String, type: Pattern): GherkinClause {
    val typeNameStatement = "type ${withoutPatternDelimiters(key)}"

    val typeDefinitionStatement = when (type) {
        is TabularPattern -> patternMapToString(type.pattern)
        is XMLPattern -> {
            "\"\"\"\n${type.toGherkinString("      ")}\n\"\"\"".trimIndent()
        }
        else -> throw ContractException("Type not recognised: $type")
    }

    return GherkinClause("$typeNameStatement\n$typeDefinitionStatement", Given)
}

private fun patternMapToString(json: Map<String, Pattern>): String {
    return json.entries.joinToString("\n") {
        "  | ${it.key.replace("|", "\\|")} | ${it.value.pattern.toString().replace("|", "\\|")} |"
    }
}

fun withFeatureClause(name: String, scenarios: String): String {
    return """Feature: $name
${scenarios.prependIndent("  ")}
"""
}

fun withScenarioClause(scenarioName: String, scenarioData: String): String {
    return """Scenario: $scenarioName
${scenarioData.prependIndent("  ")}
"""
}

fun toGherkinScenario(scenarioName: String, clauses: List<GherkinClause>, examplesList: List<ExampleDeclarations>): String {
    val groupedClauses = clauses.groupBy { it.section }

    val prefixesInOrder = listOf(Given, When, Then, Star)

    val statements = prefixesInOrder.flatMap { section ->
        val sectionClauses = groupedClauses[section] ?: emptyList()
        val prefixes = listOf(section.prefix).plus(1.until(sectionClauses.size).map { "And" })
        sectionClauses.zip(prefixes).map { (clause, prefix) -> GherkinStatement(clause.content, prefix) }
    }

    val statementString = statements.joinToString("\n") { it.toGherkinString() }

    val scenarioGherkin = when {
        hasAtLeastOneExample(examplesList) -> {
            val examplesString = toExampleGherkinString(examplesList)
            "$statementString\n\n$examplesString"
        }
        else -> statementString
    }

    return withScenarioClause(scenarioName, scenarioGherkin).trim()
}

private fun hasAtLeastOneExample(examplesList: List<ExampleDeclarations>) =
    examplesList.isNotEmpty() && examplesList.first().examples.isNotEmpty()

internal fun toExampleGherkinString(exampleDeclarationsStore: ExampleDeclarations): String {
    val entries = exampleDeclarationsStore.examples.entries.toList()
    val heading = """| ${entries.joinToString(" | ") { it.key.replace("|", "\\|") }} |"""
    val firstRow = """| ${entries.joinToString(" | ") { it.value.replace("|", "\\|") }} |"""

    return "Examples:\n$heading\n$firstRow"
}

interface Commenter {
    fun addCommentHeading(headings: String): String
    fun fromExample(example: ExampleDeclarations): String
}

class HasComments : Commenter {
    override fun addCommentHeading(headings: String): String {
        return "$headings __comment__ |"
    }

    override fun fromExample(example: ExampleDeclarations): String {
        return example.comment?.let { " ${example.comment} |" } ?: ""
    }
}

class HasNoComments : Commenter {
    override fun addCommentHeading(headings: String): String {
        return headings
    }

    override fun fromExample(example: ExampleDeclarations): String {
        return ""
    }
}

internal fun toExampleGherkinString(examplesList: List<ExampleDeclarations>): String {
    val keys = examplesList.first().examples.entries.toList().map { it.key }

    val commenter: Commenter = if(examplesList.any { it.comment != null })
        HasComments()
    else
        HasNoComments()

    val heading = commenter.addCommentHeading("""| ${keys.joinToString(" | ") { it.replace("|", "\\|") }} |""")

    val rows = examplesList.joinToString("\n") { example ->
        val values = keys.map { key -> example.examples.getValue(key) }
        val comment = commenter.fromExample(example)

        """| ${values.joinToString(" | ") { it.replace("|", "\\|") }} |$comment"""
    }

    return "Examples:\n$heading\n$rows"
}
