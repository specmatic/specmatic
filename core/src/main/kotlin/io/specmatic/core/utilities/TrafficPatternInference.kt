package io.specmatic.core.utilities

import io.specmatic.core.NoBodyValue
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.AnythingPattern
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.EmptyStringPattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.JSONArrayPattern
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.ListPattern
import io.specmatic.core.pattern.NullPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.extractCombinedExtensions
import io.specmatic.core.pattern.withoutOptionality
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import io.specmatic.core.value.fold.FieldObjectValueCase
import io.specmatic.core.value.fold.IndexedListValueCase
import io.specmatic.core.value.fold.OpaqueValueCase
import io.specmatic.core.value.fold.ScalarValueCase
import io.specmatic.core.value.fold.TextValueCase
import io.specmatic.core.value.fold.ValueVisitor
import io.specmatic.core.value.fold.XmlElementValueCase

internal data class PatternDeclaration(
    val pattern: Pattern,
    val types: Map<String, Pattern> = emptyMap(),
)

private data class PatternInferenceContext(
    val name: String,
    val types: Map<String, Pattern> = emptyMap(),
)

private data class FieldInference(
    val patterns: Map<String, Pattern> = emptyMap(),
    val types: Map<String, Pattern> = emptyMap(),
) {
    fun add(name: String, declaration: PatternDeclaration): FieldInference = copy(
        patterns = patterns + (name to declaration.pattern),
        types = declaration.types,
    )
}

/**
 * Infers the structured pattern used when converting recorded traffic to OpenAPI.
 * New Value shapes must participate in ValueVisitor traversal or they will fail through opaque().
 */
internal fun Value.toPatternDeclaration(
    name: String,
    types: Map<String, Pattern> = emptyMap(),
): PatternDeclaration = accept(TrafficPatternInferenceVisitor(PatternInferenceContext(name, types)))

private class TrafficPatternInferenceVisitor(
    override val rootContext: PatternInferenceContext,
) : ValueVisitor<PatternInferenceContext, PatternDeclaration> {
    override fun scalar(case: ScalarValueCase<out Value, PatternInferenceContext>): PatternDeclaration =
        PatternDeclaration(case.value.trafficScalarPattern(), case.context.types)

    override fun text(case: TextValueCase<out Value, PatternInferenceContext>): PatternDeclaration =
        PatternDeclaration(case.value.type(), case.context.types)

    override fun fieldObject(case: FieldObjectValueCase<out Value, PatternInferenceContext>): PatternDeclaration {
        val fields = case.fields().fold(FieldInference(types = case.context.types)) { inference, field ->
            val declaration = field.value.accept(
                visitor = this,
                context = PatternInferenceContext(field.name, inference.types),
            )
            inference.add(field.name, declaration)
        }

        return namedPattern(case.context.name, JSONObjectPattern(fields.patterns), fields.types)
    }

    override fun indexedList(case: IndexedListValueCase<out Value, PatternInferenceContext>): PatternDeclaration {
        val declarations = case.items().map { item ->
            item.value.accept(visitor = this, context = case.context)
        }

        if (declarations.isEmpty()) {
            return PatternDeclaration(JSONArrayPattern(), case.context.types)
        }

        val element = declarations.reduce(::convergePatternDeclarations)
        return PatternDeclaration(ListPattern(element.pattern), element.types)
    }

    override fun xmlElement(case: XmlElementValueCase<out XMLValue, PatternInferenceContext>): PatternDeclaration {
        val xmlNode = case.value as? XMLNode
            ?: throw ContractException("Cannot infer an OpenAPI pattern from ${case.value::class.simpleName ?: "XML value"}")
        return namedPattern(case.context.name, XMLPattern(xmlNode, case.context.name), case.context.types)
    }

    override fun opaque(case: OpaqueValueCase<out Value, PatternInferenceContext>): PatternDeclaration =
        when (case.value) {
            NoBodyValue -> PatternDeclaration(ExactValuePattern(StringValue("No body")), case.context.types)
            else -> throw ContractException("Cannot infer an OpenAPI pattern from ${case.kind}")
        }
}

private fun Value.trafficScalarPattern(): Pattern = when (this) {
    is NumberValue -> when (number) {
        is Int, is Long -> NumberPattern()
        else -> NumberPattern(isDoubleFormat = true)
    }
    else -> type()
}

private fun namedPattern(name: String, pattern: Pattern, types: Map<String, Pattern>): PatternDeclaration {
    val typeName = generateSequence(name.capitalizeFirstChar()) { "${it}_" }.first { it !in types }
    return PatternDeclaration(
        pattern = DeferredPattern("($typeName)"),
        types = types + (typeName to pattern),
    )
}

private fun convergePatternDeclarations(first: PatternDeclaration, second: PatternDeclaration): PatternDeclaration =
    PatternDeclaration(
        pattern = first.pattern,
        types = convergeNamedPatterns(first.types, second.types),
    )

private fun convergeNamedPatterns(first: Map<String, Pattern>, second: Map<String, Pattern>): Map<String, Pattern> {
    val secondOnly = second.filterKeys { it !in first }
    return first.mapValues { (name, pattern) ->
        second[name]?.let { convergePatterns(pattern, it) } ?: pattern
    } + secondOnly
}

private fun convergePatterns(first: Pattern, second: Pattern): Pattern = when {
    first is JSONObjectPattern && second is JSONObjectPattern -> JSONObjectPattern(
        convergeObjectFields(first.pattern, second.pattern),
    )
    first is JSONArrayPattern && first.pattern.isEmpty() && second is ListPattern -> second
    first is ListPattern && second is JSONArrayPattern && second.pattern.isEmpty() -> first
    first is ListPattern && second is ListPattern && first.pattern === AnythingPattern -> second
    first is ListPattern && second is ListPattern && second.pattern === AnythingPattern -> first
    first is ListPattern && second is ListPattern -> ListPattern(convergePatterns(first.pattern, second.pattern))
    first is NullPattern && second !is NullPattern -> nullablePattern(second)
    second is NullPattern && first !is NullPattern -> nullablePattern(first)
    samePatternType(first, second) -> second
    else -> first
}

private fun convergeObjectFields(first: Map<String, Pattern>, second: Map<String, Pattern>): Map<String, Pattern> {
    val firstByName = first.entries.associateBy { withoutOptionality(it.key) }
    val secondByName = second.entries.associateBy { withoutOptionality(it.key) }
    val commonNames = firstByName.keys.filter { it in secondByName }

    val common = commonNames.associateWith { name ->
        convergePatterns(firstByName.getValue(name).value, secondByName.getValue(name).value)
    }.mapKeys { (name, _) ->
        if (firstByName.getValue(name).key.endsWith("?") || secondByName.getValue(name).key.endsWith("?")) "$name?" else name
    }
    val secondOnly = secondByName.filterKeys { it !in firstByName }.map { (name, entry) -> "$name?" to entry.value }.toMap()
    val firstOnly = firstByName.filterKeys { it !in secondByName }.map { (name, entry) -> "$name?" to entry.value }.toMap()

    return common + secondOnly + firstOnly
}

private fun samePatternType(first: Pattern, second: Pattern): Boolean = when {
    first is DeferredPattern && second is DeferredPattern -> first.pattern == second.pattern
    else -> first::class == second::class
}

private fun nullablePattern(pattern: Pattern): Pattern {
    val patterns = listOf(EmptyStringPattern, pattern)
    return AnyPattern(pattern = patterns, extensions = patterns.extractCombinedExtensions())
}
