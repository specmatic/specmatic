package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.dataTypeMismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.pattern.fold.PatternVisitor
import io.specmatic.core.pattern.fold.TextPatternCase
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import javax.xml.datatype.DatatypeConstants
import javax.xml.datatype.DatatypeFactory

/** WSDL XML Schema 1.0 dates; normalization validates without rewriting authored payloads.
 * Additional date facets (for example minInclusive) remain unsupported and nonblocking.
 */
data class XsdDatePattern(val lexicalConstraints: StringPattern = StringPattern()) : Pattern, ScalarType {
    override fun <C, R> accept(visitor: PatternVisitor<C, R>, context: C): R {
        return visitor.text(TextPatternCase(pattern = this, context = context))
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(sampleData?.hasSupportedTemplate() == true)
            return Result.Success()

        return when (sampleData) {
            is StringValue -> resultOf(ruleViolation = StandardRuleViolation.TYPE_MISMATCH) {
                parse(sampleData.string, resolver)
                Result.Success()
            }
            else -> dataTypeMismatchResult(this, sampleData, resolver.mismatchMessages)
        }
    }

    override fun generate(resolver: Resolver): StringValue {
        val today = StringValue(RFC3339.currentDate())
        if (matches(today, resolver).isSuccess()) return today
        repeat(100) {
            val candidate = lexicalConstraints.generate(resolver)
            if (candidate is StringValue && matches(candidate, resolver).isSuccess()) return candidate
        }
        throw ContractException("Could not generate an XSD date satisfying the supported lexical restrictions")
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(this))

    override fun newBasedOn(resolver: Resolver): Sequence<XsdDatePattern> = sequenceOf(this)

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return scalarAnnotation(this, sequenceOf(StringPattern(), NumberPattern(), BooleanPattern(), NullPattern))
    }

    override fun parse(value: String, resolver: Resolver): StringValue {
        return attemptParse(this, value, resolver.mismatchMessages) {
            val normalized = value.trim(' ', '\t', '\r', '\n')
            require(lexicalDate.matches(normalized)) { "Invalid XSD date" }
            val calendar = factories.get().newXMLGregorianCalendar(normalized)
            require(calendar.xmlSchemaType == DatatypeConstants.DATE && calendar.isValid && calendar.eonAndYear.signum() != 0) {
                "Invalid XSD date"
            }
            val constraintResult = lexicalConstraints.matches(StringValue(normalized), resolver)
            constraintResult.throwOnFailure()
            StringValue(value)
        }
    }

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        return encompasses(this, otherPattern, thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override val typeAlias: String?
        get() = null

    override val typeName: String = "XSD date"

    override val pattern = "(xsd-date)"

    companion object {
        // The JDK parser accepts extra leading year zeros and offset minute 60.
        private val lexicalDate = Regex("""-?(?:[0-9]{4}|[1-9][0-9]{4,})-[0-9]{2}-[0-9]{2}(?:Z|[+-](?:(?:0[0-9]|1[0-3]):[0-5][0-9]|14:00))?""")
        private val factories = ThreadLocal.withInitial { DatatypeFactory.newDefaultInstance() }
    }

    override fun toString() = pattern
}
