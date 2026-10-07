package io.specmatic.core.pattern.fold

import io.specmatic.core.Resolver
import io.specmatic.core.pattern.*
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.fold.Item
import io.specmatic.core.value.fold.XmlAttribute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class PatternVisitorTest {
    @Nested
    inner class DefaultCases {
        @ParameterizedTest
        @MethodSource("io.specmatic.core.pattern.fold.PatternVisitorTest#opaquePatterns")
        fun `patterns without a structural case are opaque`(pattern: Pattern) {
            val visitor = RecordingPatternVisitor()
            val result = pattern.accept(visitor, "request")

            assertThat(result).isEqualTo(Visit(pattern, "request", "opaque"))
            assertThat(visitor.observed).isEqualTo(OpaquePatternCase(pattern, "request"))
        }

        @Test
        fun `deferred pattern is represented without resolving it`() {
            val pattern = DeferredPattern("(User)")
            val userPattern = StringPattern()

            val resolver = Resolver(newPatterns = mapOf("(User)" to userPattern))
            val visitor = RecordingPatternVisitor()

            val result = pattern.accept(visitor, "request")
            val case = visitor.observed as DeferredPatternCase<*, String>

            assertThat(result).isEqualTo(Visit(pattern, "request", "deferred"))
            assertThat(case.resolve(resolver)).isEqualTo(userPattern)
            assertThat(case.resolveAndVisit(visitor, resolver)).isEqualTo(Visit(userPattern, "request", "text"))
        }
    }

    @Nested
    inner class ScalarAndTextCases {
        @ParameterizedTest
        @MethodSource("io.specmatic.core.pattern.fold.PatternVisitorTest#scalarPatterns")
        fun `scalar patterns have a scalar case`(pattern: Pattern) {
            val visitor = RecordingPatternVisitor()

            val result = pattern.accept(visitor, "body")

            assertThat(result).isEqualTo(Visit(pattern, "body", "scalar"))
            assertThat(visitor.observed).isEqualTo(ScalarPatternCase(pattern, "body"))
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.core.pattern.fold.PatternVisitorTest#textPatterns")
        fun `text patterns have a text case`(pattern: Pattern) {
            val visitor = RecordingPatternVisitor()

            val result = pattern.accept(visitor, "body")

            assertThat(result).isEqualTo(Visit(pattern, "body", "text"))
            assertThat(visitor.observed).isEqualTo(TextPatternCase(pattern, "body"))
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.core.pattern.fold.PatternVisitorTest#constantPatterns")
        fun `exact values have a constant case`(pattern: ExactValuePattern) {
            val visitor = RecordingPatternVisitor()

            val result = pattern.accept(visitor, "body")

            assertThat(result).isEqualTo(Visit(pattern, "body", "constant"))
            assertThat(visitor.observed).isEqualTo(ConstantPatternCase(pattern, "body"))
        }
    }

    @Nested
    inner class ObjectCases {
        @Test
        fun `object fields expose names patterns and required-ness`() {
            val idPattern = StringPattern()
            val countPattern = NumberPattern()
            val visitor = RecordingPatternVisitor()

            val pattern = JSONObjectPattern(mapOf("id" to idPattern, "count?" to countPattern))
            val result = pattern.accept(visitor, "request")
            val case = visitor.observed as ObjectPatternCase<*, String>

            assertThat(result).isEqualTo(Visit(pattern, "request", "objectProperties"))
            assertThat(case.properties).isEqualTo(
                listOf(
                    ObjectPropertyPattern("id", idPattern, required = true),
                    ObjectPropertyPattern("count", countPattern, required = false),
                ),
            )

            assertThat(case.patternForProperty("count")).isEqualTo(countPattern)
            assertThat(case.patternForProperty("unknown")).isNull()
            assertThat(case.projectProperties(visitor)).isEqualTo(
                listOf(
                    Visit(idPattern, "request.id", "text"),
                    Visit(countPattern, "request.count", "scalar"),
                ),
            )

            assertThat(case.projectPropertyValue(visitor, "id"))
                .isEqualTo(Visit(idPattern, "request.id", "text"))
        }

        @Test
        fun `nested patterns carry visitor context down each projection`() {
            val pricePattern = NumberPattern()
            val rowPattern = JSONObjectPattern(mapOf("price" to pricePattern))
            val rowsPattern = ListPattern(rowPattern)

            val visitor = RecordingPatternVisitor()
            val requestPattern = JSONObjectPattern(mapOf("rows" to rowsPattern))
            requestPattern.accept(visitor, "request")

            val requestCase = visitor.observed as ObjectPatternCase<*, String>
            val rowsProjection = requestCase.projectPropertyValue(visitor, "rows")

            val rowsCase = visitor.observed as RepeatedListPatternCase<*, String>
            val rowProjection = rowsCase.projectItem(visitor, 0)

            val rowCase = visitor.observed as ObjectPatternCase<*, String>
            val priceProjection = rowCase.projectPropertyValue(visitor, "price")

            assertThat(rowsProjection).isEqualTo(Visit(rowsPattern, "request.rows", "repeatedList"))
            assertThat(rowProjection).isEqualTo(Visit(rowPattern, "request.rows[0]", "objectProperties"))
            assertThat(priceProjection).isEqualTo(Visit(pricePattern, "request.rows[0].price", "scalar"))
        }

        @Test
        fun `object additional properties select constrained free form or no child`() {
            val closedObject = JSONObjectPattern()
            val constrainedPattern = StringPattern()
            val freeFormObject = JSONObjectPattern(additionalProperties = AdditionalProperties.FreeForm)
            val constrainedObject = JSONObjectPattern(additionalProperties = AdditionalProperties.PatternConstrained(constrainedPattern))

            val constrainedProjection = RecordingPatternVisitor()
            constrainedObject.accept(constrainedProjection)

            val constrainedObjectCase = constrainedProjection.observed as ObjectPatternCase<*, String>
            assertThat(constrainedObjectCase.additionalProperties)
                .isEqualTo(AdditionalProperties.PatternConstrained(constrainedPattern))
            assertThat(constrainedObjectCase.patternForProperty("extra")).isEqualTo(constrainedPattern)
            assertThat(constrainedObjectCase.projectPropertyValue(constrainedProjection, "extra"))
                .isEqualTo(Visit(constrainedPattern, "root.extra", "text"))

            val freeFormProjection = RecordingPatternVisitor()
            freeFormObject.accept(freeFormProjection)

            val freeFormCase = freeFormProjection.observed as ObjectPatternCase<*, String>
            assertThat(freeFormCase.additionalProperties).isEqualTo(AdditionalProperties.FreeForm)
            assertThat(freeFormCase.patternForProperty("extra")).isNull()
            assertThat(freeFormCase.projectPropertyValue(freeFormProjection, "extra"))
                .isNull()
            val extraValue = JSONObjectValue(mapOf("name" to StringValue("example")))
            assertThat(freeFormCase.patternForProperty("extra", extraValue)).isEqualTo(extraValue.deepPattern())
            assertThat(freeFormCase.projectPropertyValue(freeFormProjection, "extra", extraValue))
                .isEqualTo(Visit(extraValue.deepPattern(), "root.extra", "objectProperties"))

            val closedProjection = RecordingPatternVisitor()
            closedObject.accept(closedProjection)
            val closedCase = closedProjection.observed as ObjectPatternCase<*, String>
            assertThat(closedCase.additionalProperties).isEqualTo(AdditionalProperties.NoAdditionalProperties)
            assertThat(closedCase.patternForProperty("extra")).isNull()
        }

        @Test
        fun `tabular fields use the same object projection with header lookup`() {
            val cellPattern = StringPattern()
            val optionalCellPattern = NumberPattern()
            val table = TabularPattern(mapOf("name" to cellPattern, "age?" to optionalCellPattern))
            val visitor = RecordingPatternVisitor()

            table.accept(visitor)
            val case = visitor.observed as ObjectPatternCase<*, String>
            assertThat(case.pattern).isEqualTo(table)
            assertThat(case.patternForProperty("name")).isEqualTo(cellPattern)
            assertThat(case.patternForProperty("missing")).isNull()
            assertThat(case.properties).isEqualTo(
                listOf(
                    ObjectPropertyPattern("name", cellPattern, required = true),
                    ObjectPropertyPattern("age", optionalCellPattern, required = false),
                ),
            )
        }
    }

    @Nested
    inner class IndexedCases {
        @Test
        fun `list pattern has one repeated item pattern for arbitrary indexes`() {
            val itemPattern = StringPattern()
            val pattern = ListPattern(itemPattern)
            val visitor = RecordingPatternVisitor()

            pattern.accept(visitor, "body")
            val case = visitor.observed as RepeatedListPatternCase<*, String>

            assertThat(case.itemPattern).isEqualTo(itemPattern)
            assertThat(case.projectItem(visitor, 0)).isEqualTo(Visit(itemPattern, "body[0]", "text"))
            assertThat(case.projectItem(visitor, 4)).isEqualTo(Visit(itemPattern, "body[4]", "text"))
        }

        @Test
        fun `fixed array pattern exposes each declared index only`() {
            val firstPattern = StringPattern()
            val secondPattern = NumberPattern()
            val pattern = JSONArrayPattern(listOf(firstPattern, secondPattern))
            val visitor = RecordingPatternVisitor()

            pattern.accept(visitor, "body")
            val case = visitor.observed as IndexedListPatternCase<*, String>

            assertThat(case.items).isEqualTo(listOf(Item(0, firstPattern), Item(1, secondPattern)))
            assertThat(listOf(case.patternFor(0), case.patternFor(1), case.patternFor(2)))
                .isEqualTo(listOf(firstPattern, secondPattern, null))

            assertThat(case.projectItems(visitor)).isEqualTo(
                listOf(
                    Item(0, Visit(firstPattern, "body[0]", "text")),
                    Item(1, Visit(secondPattern, "body[1]", "scalar")),
                ),
            )
        }
    }

    @Nested
    inner class CompositionCases {
        @Test
        fun `oneOf selects a branch while anyOf preserves its alternatives`() {
            val first = JSONObjectPattern(mapOf("kind" to ExactValuePattern(StringValue("first"), discriminator = true)))
            val second = JSONObjectPattern(mapOf("kind" to ExactValuePattern(StringValue("second"), discriminator = true)))
            val patterns = listOf(first, second)

            val discriminator = Discriminator.create("kind", setOf("first", "second"), emptyMap())
            val anyPattern = AnyPattern(patterns, discriminator = discriminator)
            val anyOfPattern = AnyOfPattern(patterns, discriminator = discriminator)

            val anyVisitor = RecordingPatternVisitor()
            anyPattern.accept(anyVisitor, "choice")

            val anyCase = anyVisitor.observed as OneOfPatternCase<*, String>
            assertThat(anyVisitor.lastKind).isEqualTo("oneOf")
            assertThat(anyCase.alternatives).isEqualTo(patterns)
            assertThat(anyCase.discriminator).isEqualTo(discriminator)
            assertThat(anyCase.choosePattern(JSONObjectValue(mapOf("kind" to StringValue("second"))), Resolver())).isEqualTo(second)
            assertThat(anyCase.projectAlternatives(anyVisitor)).isEqualTo(
                listOf(
                    Visit(
                        pattern = first,
                        context = "choice|0",
                        kind = "objectProperties"
                    ), Visit(
                        pattern = second,
                        context = "choice|1",
                        kind = "objectProperties"
                    )
                ),
            )

            val anyOfVisitor = RecordingPatternVisitor()
            anyOfPattern.accept(anyOfVisitor, "choice")
            val anyOfCase = anyOfVisitor.observed as AnyOfPatternCase<*, String>
            assertThat(anyOfVisitor.lastKind).isEqualTo("anyOf")
            assertThat(anyOfCase.alternatives).isEqualTo(patterns)
            assertThat(anyOfCase.discriminator).isEqualTo(discriminator)
            assertThat(anyOfCase.projectAlternatives(anyOfVisitor)).isEqualTo(
                listOf(
                    Visit(
                        pattern = first,
                        context = "choice|0",
                        kind = "objectProperties"
                    ), Visit(
                        pattern = second,
                        context = "choice|1",
                        kind = "objectProperties"
                    )
                ),
            )
        }

        @Test
        fun `anyOf preserves every matching alternative`() {
            val first = NumberPattern()
            val second = AnyValuePattern
            val pattern = AnyOfPattern(listOf(first, second))
            val visitor = RecordingPatternVisitor()

            pattern.accept(visitor, "choice")
            val case = visitor.observed as AnyOfPatternCase<*, String>

            val value = NumberValue(3)
            assertThat(
                listOf(
                    Resolver().matchesPattern(first, value).isSuccess(),
                    Resolver().matchesPattern(second, value).isSuccess()
                )
            ).isEqualTo(listOf(true, true))

            assertThat(pattern.matches(value, Resolver()).isSuccess()).isEqualTo(true)
            assertThat(case.choosePattern(value, Resolver())).isEqualTo(first)
            assertThat(case.projectAlternatives(visitor)).isEqualTo(
                listOf(
                    Visit(first, "choice|0", "scalar"),
                    Visit(second, "choice|1", "opaque"),
                ),
            )
        }

        @Test
        fun `enum delegates to its one of pattern`() {
            val enumPattern = EnumPattern(listOf(StringValue("red"), StringValue("blue")))
            val visitor = RecordingPatternVisitor()

            enumPattern.accept(visitor, "enum")
            val enumCase = visitor.observed as DelegatedPatternCase<*, String>

            assertThat(enumCase.pattern).isEqualTo(enumPattern)
            assertThat(enumCase.delegate).isEqualTo(enumPattern.pattern)
            assertThat(enumCase.projectDelegate(visitor)).isEqualTo(Visit(enumPattern.pattern, "enum", "oneOf"))

            val alternativesCase = visitor.observed as OneOfPatternCase<*, String>
            assertThat(alternativesCase.alternatives).isEqualTo(enumPattern.pattern.pattern)
            assertThat(alternativesCase.choosePattern(StringValue("blue"), Resolver()))
                .isEqualTo(ExactValuePattern(StringValue("blue")))
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.core.pattern.fold.PatternVisitorTest#delegatedPatterns")
        fun `wrapper patterns expose their delegate without losing the wrapper`(pattern: Pattern, delegate: Pattern) {
            val visitor = RecordingPatternVisitor()

            val result = pattern.accept(visitor, "query")
            val case = visitor.observed as DelegatedPatternCase<*, String>

            assertThat(result).isEqualTo(Visit(pattern, "query", "delegated"))
            assertThat(case.pattern).isEqualTo(pattern)
            assertThat(case.delegate).isEqualTo(delegate)
            assertThat(case.projectDelegate(visitor)).isEqualTo(Visit(delegate, "query", "text"))
        }
    }

    @Nested
    inner class XmlCases {
        @Test
        fun `xml element exposes attributes and indexed children`() {
            val attributePattern = StringPattern()
            val childPattern = NumberPattern()
            val xmlPattern = XMLPattern(
                pattern = XMLTypeData(
                    realName = "root",
                    attributes = mapOf("id" to attributePattern),
                    nodes = listOf(childPattern),
                ),
            )

            val visitor = RecordingPatternVisitor()
            val result = xmlPattern.accept(visitor, "xml")
            val case = visitor.observed as XmlElementPatternCase<*, String>

            assertThat(result).isEqualTo(Visit(xmlPattern, "xml", "xmlElement"))
            assertThat(case.attributes).isEqualTo(listOf(XmlAttribute("id", attributePattern)))
            assertThat(case.children).isEqualTo(listOf(Item(0, childPattern)))
            assertThat(case.projectAttributes(visitor)).isEqualTo(
                listOf(
                    element = XmlAttribute(
                        name = "id",
                        value = Visit(attributePattern, "xml.@id", "text")
                    )
                ),
            )
            assertThat(case.projectChildren(visitor)).isEqualTo(
                listOf(
                    element = Item(
                        index = 0,
                        value = Visit(childPattern, "xml[0]", "scalar")
                    )
                ),
            )
        }

        @Test
        fun `xml sequence and substitution group expose their child patterns`() {
            val sequenceVisitor = RecordingPatternVisitor()
            val firstPattern = XMLPattern(pattern = XMLTypeData(realName = "first"))
            val secondPattern = XMLPattern(pattern = XMLTypeData(realName = "second"))
            val sequence = XMLSequencePattern(listOf(firstPattern, secondPattern))

            sequence.accept(sequenceVisitor, "xml")
            val sequenceCase = sequenceVisitor.observed as IndexedListPatternCase<*, String>
            assertThat(sequenceCase.items).isEqualTo(listOf(Item(0, firstPattern), Item(1, secondPattern)))
            assertThat(sequenceCase.patternFor(0)).isEqualTo(firstPattern)
            assertThat(sequenceCase.patternFor(2)).isNull()

            val substitutionVisitor = RecordingPatternVisitor()
            val substitutionGroup = XMLSubstitutionGroupPattern("head", listOf(firstPattern, secondPattern))
            substitutionGroup.accept(substitutionVisitor, "xml")

            val substitutionCase = substitutionVisitor.observed as OneOfPatternCase<*, String>
            assertThat(substitutionCase.alternatives).isEqualTo(listOf(firstPattern, secondPattern))
        }

        @Test
        fun `xml choice group preserves branch boundaries while projecting children`() {
            val firstPattern = XMLPattern(pattern = XMLTypeData(realName = "first"))
            val secondPattern = XMLPattern(pattern = XMLTypeData(realName = "second"))
            val thirdPattern = XMLPattern(pattern = XMLTypeData(realName = "third"))
            val branches = listOf(listOf(firstPattern, secondPattern), listOf(thirdPattern))
            val choiceGroup = XMLChoiceGroupPattern(branches)
            val visitor = RecordingPatternVisitor()

            choiceGroup.accept(visitor, "xml")
            val case = visitor.observed as AlternativeSequencesPatternCase<*, String>
            assertThat(case.alternatives).isEqualTo(branches)
            assertThat(case.projectAlternatives(visitor)).isEqualTo(
                listOf(
                    listOf(
                        Visit(firstPattern, "xml|0[0]", "xmlElement"),
                        Visit(secondPattern, "xml|0[1]", "xmlElement"),
                    ),
                    listOf(Visit(thirdPattern, "xml|1[0]", "xmlElement")),
                ),
            )
        }
    }

    companion object {
        @JvmStatic
        fun opaquePatterns(): Stream<Arguments> = Stream.of(
            Arguments.of(AnyValuePattern),
        )

        @JvmStatic
        fun scalarPatterns(): Stream<Arguments> = Stream.of(
            Arguments.of(NumberPattern()),
            Arguments.of(BooleanPattern()),
            Arguments.of(NullPattern),
            Arguments.of(BinaryPattern()),
        )

        @JvmStatic
        fun textPatterns(): Stream<Arguments> = Stream.of(
            Arguments.of(StringPattern()),
            Arguments.of(EmptyStringPattern),
            Arguments.of(DatePattern),
            Arguments.of(DateTimePattern),
            Arguments.of(TimePattern),
            Arguments.of(UUIDPattern),
            Arguments.of(URLPattern()),
            Arguments.of(Base64StringPattern()),
        )

        @JvmStatic
        fun constantPatterns(): Stream<Arguments> = Stream.of(
            Arguments.of(ExactValuePattern(StringValue("fixed"))),
            Arguments.of(ExactValuePattern(NumberValue(3))),
            Arguments.of(ExactValuePattern(JSONObjectValue(mapOf("fixed" to StringValue("value"))))),
        )

        @JvmStatic
        fun delegatedPatterns(): Stream<Arguments> {
            val stringPattern = StringPattern()
            return Stream.of(
                Arguments.of(PatternInStringPattern(stringPattern), stringPattern),
                Arguments.of(CsvPattern(stringPattern), stringPattern),
                Arguments.of(QueryParameterScalarPattern(stringPattern), stringPattern),
                Arguments.of(LookupRowPattern(stringPattern), stringPattern),
                Arguments.of(AnyNonNullJSONValue(stringPattern), stringPattern),
                Arguments.of(EmailPattern(stringPattern), stringPattern),
                Arguments.of(RegexConstrainedPattern(stringPattern, ".*", Resolver(), eagerRegexValidation = false), stringPattern),
            )
        }
    }
}

private data class Visit(val pattern: Pattern, val context: String, val kind: String)
private class RecordingPatternVisitor(override val rootContext: String = "root") : PatternVisitor<String, Visit> {
    var observed: PatternCase<Pattern, String>? = null
    var lastKind: String? = null

    override fun opaque(case: OpaquePatternCase<out Pattern, String>): Visit = capture(case, "opaque")
    override fun scalar(case: ScalarPatternCase<out Pattern, String>): Visit = capture(case, "scalar")
    override fun text(case: TextPatternCase<out Pattern, String>): Visit = capture(case, "text")
    override fun constant(case: ConstantPatternCase<out Pattern, String>): Visit = capture(case, "constant")
    override fun objectProperties(case: ObjectPatternCase<out Pattern, String>): Visit = capture(case, "objectProperties")
    override fun indexedList(case: IndexedListPatternCase<out Pattern, String>): Visit = capture(case, "items")
    override fun repeatedList(case: RepeatedListPatternCase<out Pattern, String>): Visit = capture(case, "repeatedList")
    override fun oneOf(case: OneOfPatternCase<out Pattern, String>): Visit = capture(case, "oneOf")
    override fun anyOf(case: AnyOfPatternCase<out Pattern, String>): Visit = capture(case, "anyOf")
    override fun alternativeSequences(case: AlternativeSequencesPatternCase<out Pattern, String>): Visit = capture(case, "alternativeSequences")
    override fun delegated(case: DelegatedPatternCase<out Pattern, String>): Visit = capture(case, "delegated")
    override fun deferred(case: DeferredPatternCase<out Pattern, String>): Visit = capture(case, "deferred")
    override fun xmlElement(case: XmlElementPatternCase<out Pattern, String>): Visit = capture(case, "xmlElement")

    override fun contextForProperty(currentContext: String, name: String): String = "$currentContext.$name"
    override fun contextForIndex(currentContext: String, index: Int): String = "$currentContext[$index]"
    override fun contextForAlternative(currentContext: String, index: Int): String = "$currentContext|$index"
    override fun contextForXmlChild(currentContext: String, index: Int): String = "$currentContext[$index]"
    override fun contextForXmlAttribute(currentContext: String, name: String): String = "$currentContext.@$name"

    private fun capture(case: PatternCase<Pattern, String>, kind: String): Visit {
        observed = case
        lastKind = kind
        return Visit(case.pattern, case.context, kind)
    }
}
