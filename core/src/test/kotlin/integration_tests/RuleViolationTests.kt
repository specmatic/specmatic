package integration_tests

import integration_tests.CompositePatternTestCase.Companion.parseAndExtractCompositePatterns
import integration_tests.RuleViolationCase.Companion.violationTestCase
import io.specmatic.core.*
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.toValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.Value
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

data class RuleViolationAssertion(
    private val path: String? = null,
    private val totalViolations: Int? = null,
    private val shouldContainRuleViolation: List<RuleViolation>,
    private val shouldNotContainRuleViolation: List<RuleViolation>,
    private val severity: IssueSeverity
) {
    fun assertViolation(result: Result) {
        val reportText = result.reportString()
        val issues = result.toIssues()
        assertViolationInText(reportText)
        assertViolationInIssues(issues)
        assertSeverity(issues)
        assertTotalViolations(issues)
    }

    private fun assertViolationInText(text: String) {
        assertThat(shouldContainRuleViolation).allSatisfy { ruleViolation ->
            val violationReport = RuleViolationReport(ruleViolations = setOf(ruleViolation))
            val ruleViolationString = violationReport.toText().orEmpty()
            assertThat(text).containsIgnoringWhitespaces(ruleViolationString)
        }

        assertThat(shouldNotContainRuleViolation).allSatisfy { ruleViolation ->
            val violationReport = RuleViolationReport(ruleViolations = setOf(ruleViolation))
            val ruleViolationString = violationReport.toText().orEmpty()
            assertThat(text.normalizeWs()).doesNotContain(ruleViolationString.normalizeWs())
        }
    }

    private fun assertViolationInIssues(issues: List<Issue>) {
        assertThat(shouldContainRuleViolation).allSatisfy { ruleViolation ->
            val matchingIssue = findMatchingIssue(issues) ?: fail("No matching issue found for path $path")
            val violationSnapShot = RuleViolationReport(ruleViolations = setOf(ruleViolation)).toSnapShots().first()
            assertThat(matchingIssue.ruleViolations).contains(violationSnapShot)
        }

        assertThat(shouldNotContainRuleViolation).allSatisfy { ruleViolation ->
            val matchingIssue = findMatchingIssue(issues) ?: fail("No matching issue found for path $path")
            val violationSnapShot = RuleViolationReport(ruleViolations = setOf(ruleViolation)).toSnapShots().first()
            assertThat(matchingIssue.ruleViolations).doesNotContain(violationSnapShot)
        }
    }

    private fun assertTotalViolations(issues: List<Issue>) {
        if (totalViolations == null) return
        val matchingIssue = findMatchingIssue(issues) ?: fail("No matching issue found for path $path")
        assertThat(matchingIssue.ruleViolations.size).isEqualTo(totalViolations)
    }

    private fun assertSeverity(issues: List<Issue>) {
        val matchingIssue = findMatchingIssue(issues) ?: fail("No matching issue found for path $path")
        assertThat(matchingIssue.severity).isEqualTo(severity)
    }

    private fun findMatchingIssue(issues: List<Issue>): Issue? {
        return issues.firstOrNull { issue ->
            issue.breadCrumb == path || issue.path.joinToString(prefix = "/", separator = "/") == path || (path == null && issue.path.isEmpty())
        }
    }

    private fun String.normalizeWs() = replace("\\s+".toRegex(), " ")

    class Builder(private val path: String? = null) {
        private var ruleViolation: List<RuleViolation> = emptyList()
        private var shouldNotContainRuleViolation: List<RuleViolation> = emptyList()
        private var totalViolations: Int? = null
        private var severity: IssueSeverity = IssueSeverity.ERROR

        fun toContainViolation(ruleViolation: RuleViolation) {
            this.ruleViolation += ruleViolation
        }

        fun toNotContainViolation(ruleViolation: RuleViolation) {
            this.shouldNotContainRuleViolation += ruleViolation
        }

        fun toHaveSeverity(severity: IssueSeverity) {
            this.severity = severity
        }

        fun totalViolations(totalViolations: Int) {
            this.totalViolations = totalViolations
        }

        fun build(): RuleViolationAssertion {
            return RuleViolationAssertion(path, totalViolations, ruleViolation, shouldNotContainRuleViolation, severity)
        }
    }
}

data class PatternCheck(val patternName: String, val value: Value, val assertions: List<RuleViolationAssertion>) {
    class Builder(private val name: String) {
        private val assertions = mutableListOf<RuleViolationAssertion>()
        private var value: Value = NullValue

        fun withValue(value: Any?) {
            this.value = toValue(value)
        }

        fun expect(path: String? = null, block: RuleViolationAssertion.Builder.() -> Unit) {
            val builder = RuleViolationAssertion.Builder(path)
            block(builder)
            assertions += builder.build()
        }

        fun build() = PatternCheck(name, value, assertions)
    }
}

data class RuleViolationCase(val patternTestCase: CompositePatternTestCase, val resolverModifier: (Resolver) -> Resolver, val checks: List<PatternCheck>) {
    fun assertViolation() {
        val (patterns, resolver) = parseAndExtractCompositePatterns(OpenApiVersion.OAS31, patternTestCase)
        val updatedResolver = resolverModifier(resolver)
        checks.forEach { check ->
            val pattern = patterns[check.patternName] ?: fail("Pattern '${check.patternName}' not found")
            val result = pattern.matches(check.value, updatedResolver)
            logger.log("Checking ${check.patternName} with value ${check.value}")
            logger.log("Result: ${result.reportString()}")
            logger.boundary()
            check.assertions.forEach { it.assertViolation(result) }
        }
    }

    class Builder {
        private var patternCaseBuilder: CompositePatternTestCase.Builder = CompositePatternTestCase.Builder()
        private var resolverModifier: (Resolver) -> Resolver = { it }
        private val checks = mutableListOf<PatternCheck>()

        fun withPattern(name: String, block: MutableMap<String, Any?>.() -> Unit) {
            if (patternCaseBuilder.containsSchema(name)) fail("Pattern '$name' already exists")
            patternCaseBuilder.schema(name) {
                schema(block)
            }
        }

        fun forPattern(name: String, block: PatternCheck.Builder.() -> Unit) {
            val builder = PatternCheck.Builder(name)
            block(builder)
            checks += builder.build()
        }

        fun withResolver(block: (Resolver) -> Resolver) {
            this.resolverModifier = block
        }

        fun build(): RuleViolationCase {
            val patternTestCase = patternCaseBuilder.build()
            return RuleViolationCase(patternTestCase, resolverModifier, checks)
        }
    }

    companion object {
        fun violationTestCase(name: String, block: Builder.() -> Unit): List<Arguments> {
            val builder = Builder()
            block(builder)
            return listOf(Arguments.of(Named.of(name, builder.build())))
        }
    }
}

class RuleViolationTests {
    @ParameterizedTest
    @MethodSource("standardRuleViolationTestCases")
    fun standard_rule_violations_case(case: RuleViolationCase, info: TestInfo) = case.assertViolation()

    companion object {
        @JvmStatic
        fun standardRuleViolationTestCases(): Stream<Arguments> {
            return listOf(
                violationTestCase(name = "Type Mismatch") {
                    withPattern(name = "TEST") {
                        put("type", "number")
                    }
                    forPattern(name = "TEST") {
                        withValue("ThisShouldBeTen")
                        expect {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.TYPE_MISMATCH)
                        }
                    }
                },
                violationTestCase(name = "Value Mismatch") {
                    withPattern(name = "TEST") {
                        put("type", "string")
                        put("format", "date")
                    }
                    forPattern(name = "TEST") {
                        withValue("This-Should-Be-A-Date")
                        expect {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.VALUE_MISMATCH)
                        }
                    }
                },
                violationTestCase(name = "Constraint Violation") {
                    withPattern(name = "TEST") {
                        put("type", "string")
                        put("minLength", 10)
                    }
                    forPattern(name = "TEST") {
                        withValue("short")
                        expect {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.CONSTRAINT_VIOLATION)
                        }
                    }
                },

                violationTestCase(name = "Missing Required Property") {
                    withPattern(name = "TEST") {
                        put("type", "object")
                        put("properties", mapOf("name" to mapOf("type" to "string"), "age" to mapOf("type" to "integer")))
                        put("required", listOf("name"))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("age" to 10))
                        expect(path = "/name") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.REQUIRED_PROPERTY_MISSING)
                        }
                    }
                },
                violationTestCase(name = "Missing Optional Property") {
                    withPattern(name = "TEST") {
                        put("type", "object")
                        put("properties", mapOf("name" to mapOf("type" to "string"), "age" to mapOf("type" to "integer")))
                        put("required", listOf("name"))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("name" to "John"))
                        withResolver { it.withAllPatternsAsMandatory() }
                        expect(path = "/age") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.OPTIONAL_PROPERTY_MISSING)
                            toHaveSeverity(IssueSeverity.WARNING)
                        }
                    }
                },
                violationTestCase(name = "Contains Unknown Property") {
                    withPattern(name = "TEST") {
                        put("type", "object")
                        put("properties", mapOf("name" to mapOf("type" to "string"), "age" to mapOf("type" to "integer")))
                        put("required", listOf("name"))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("name" to "John", "unknown" to "property"))
                        expect(path = "/unknown") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.UNKNOWN_PROPERTY)
                        }
                    }
                },

                violationTestCase(name = "Discriminator Property Missing") {
                    withPattern(name = "Base") {
                        put("type", "object")
                        put("properties", mapOf("discriminator" to mapOf("type" to "string")))
                        put("required", listOf("type"))
                    }
                    withPattern(name = "Cat") {
                        put("type", "object")
                        put("allOf", listOf(mapOf("\$ref" to "#/patterns/Base")))
                        put("properties", mapOf("whiskers" to mapOf("type" to "integer")))
                        put("required", listOf("whiskers"))
                    }
                    withPattern(name = "Dog") {
                        put("type", "object")
                        put("allOf", listOf(mapOf("\$ref" to "#/patterns/Base")))
                        put("properties", mapOf("bark" to mapOf("type" to "boolean")))
                        put("required", listOf("bark"))
                    }
                    withPattern(name = "Animal") {
                        put("oneOf", listOf(mapOf("\$ref" to "#/patterns/Cat"), mapOf("\$ref" to "#/patterns/Dog")))
                        put("discriminator", mapOf("propertyName" to "type", "mapping" to mapOf("Cat" to "#/patterns/Cat", "Dog" to "#/patterns/Dog")))
                    }

                    forPattern(name = "Animal") {
                        withValue(mapOf("beakLength" to 10))
                        expect(path = "/type") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.MISSING_DISCRIMINATOR)
                        }
                    }
                },
                violationTestCase(name = "Discriminator Mismatch") {
                    withPattern(name = "Base") {
                        put("type", "object")
                        put("properties", mapOf("discriminator" to mapOf("type" to "string")))
                        put("required", listOf("type"))
                    }
                    withPattern(name = "Cat") {
                        put("type", "object")
                        put("allOf", listOf(mapOf("\$ref" to "#/patterns/Base")))
                        put("properties", mapOf("whiskers" to mapOf("type" to "integer")))
                        put("required", listOf("whiskers"))
                    }
                    withPattern(name = "Dog") {
                        put("type", "object")
                        put("allOf", listOf(mapOf("\$ref" to "#/patterns/Base")))
                        put("properties", mapOf("bark" to mapOf("type" to "boolean")))
                        put("required", listOf("bark"))
                    }
                    withPattern(name = "Animal") {
                        put("oneOf", listOf(mapOf("\$ref" to "#/patterns/Cat"), mapOf("\$ref" to "#/patterns/Dog")))
                        put("discriminator", mapOf("propertyName" to "type", "mapping" to mapOf("Cat" to "#/patterns/Cat", "Dog" to "#/patterns/Dog")))
                    }

                    forPattern(name = "Animal") {
                        withValue(mapOf("type" to "Parrot", "beakLength" to 10))
                        expect(path = "/type") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.DISCRIMINATOR_MISMATCH)
                        }
                    }
                },
                violationTestCase(name = "Invalid Discriminator Setup") {
                    withPattern(name = "Cat") {
                        put("type", "object")
                        put("properties", mapOf("whiskers" to mapOf("type" to "integer")))
                        put("required", listOf("whiskers"))
                    }
                    withPattern(name = "Dog") {
                        put("type", "object")
                        put("properties", mapOf("bark" to mapOf("type" to "boolean")))
                        put("required", listOf("bark"))
                    }
                    withPattern(name = "Animal") {
                        put("oneOf", listOf(mapOf("\$ref" to "#/patterns/Cat"), mapOf("\$ref" to "#/patterns/Dog")))
                        put("discriminator", mapOf("propertyName" to "type", "mapping" to mapOf("Cat" to "#/patterns/Cat", "Dog" to "#/patterns/Dog")))
                    }

                    forPattern(name = "Animal") {
                        withValue(mapOf("type" to "Cat", "beakLength" to 10))
                        expect(path = "/type") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.INVALID_DISCRIMINATOR_SETUP)
                        }
                    }
                },
                violationTestCase(name = "OneOf Value Mismatch") {
                    withPattern(name = "OneOfPattern") {
                        put("oneOf", listOf(mapOf("type" to "number"), mapOf("type" to "string")))
                    }
                    forPattern(name = "OneOfPattern") {
                        withValue(true)
                        expect {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.ONE_OF_VALUE_MISMATCH)
                        }
                    }
                },

                violationTestCase(name = "AnyOf Unknown Property") {
                    withPattern(name = "OptionA") {
                        put("type", "object")
                        put("properties", mapOf("a" to mapOf("type" to "string")))
                    }
                    withPattern(name = "OptionB") {
                        put("type", "object")
                        put("properties", mapOf("b" to mapOf("type" to "number")))
                    }
                    withPattern(name = "AnyOfPattern") {
                        put("anyOf", listOf(
                            mapOf("\$ref" to "#/patterns/OptionA"),
                            mapOf("\$ref" to "#/patterns/OptionB")
                        ))
                    }

                    forPattern(name = "AnyOfPattern") {
                        withValue(mapOf("c" to 10))
                        expect(path = "/c") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.ANY_OF_UNKNOWN_KEY)
                        }
                    }
                },
                violationTestCase(name = "AnyOf No Matching Schema") {
                    withPattern(name = "OptionA") {
                        put("type", "object")
                        put("properties", mapOf("common" to mapOf("type" to "string")))
                    }
                    withPattern(name = "OptionB") {
                        put("type", "object")
                        put("properties", mapOf("common" to mapOf("type" to "number")))
                    }
                    withPattern(name = "AnyOfPattern") {
                        put("anyOf", listOf(
                            mapOf("\$ref" to "#/patterns/OptionA"),
                            mapOf("\$ref" to "#/patterns/OptionB")
                        ))
                    }

                    forPattern(name = "AnyOfPattern") {
                        withValue(mapOf("common" to true))
                        expect(path = "/common") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.ANY_OF_NO_MATCHING_SCHEMA)
                        }
                    }
                },
            ).flatten().stream()
        }
    }
}
