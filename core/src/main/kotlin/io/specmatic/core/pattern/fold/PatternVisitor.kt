package io.specmatic.core.pattern.fold

import io.specmatic.core.pattern.Pattern

interface PatternVisitor<C, R> {
    val rootContext: C

    fun opaque(case: OpaquePatternCase<out Pattern, C>): R

    fun scalar(case: ScalarPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun text(case: TextPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun constant(case: ConstantPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun objectProperties(case: ObjectPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun indexedList(case: IndexedListPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun repeatedList(case: RepeatedListPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun oneOf(case: OneOfPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun anyOf(case: AnyOfPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun alternativeSequences(case: AlternativeSequencesPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun delegated(case: DelegatedPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun deferred(case: DeferredPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun xmlElement(case: XmlElementPatternCase<out Pattern, C>): R =
        opaque(OpaquePatternCase(case.pattern, case.context))

    fun contextForProperty(currentContext: C, name: String): C = currentContext

    fun contextForIndex(currentContext: C, index: Int): C = currentContext

    fun contextForAlternative(currentContext: C, index: Int): C = currentContext

    fun contextForXmlChild(currentContext: C, index: Int): C = currentContext

    fun contextForXmlAttribute(currentContext: C, name: String): C = currentContext
}
