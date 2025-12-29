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
            assertThat(matchingIssue).anySatisfy { assertThat(it.ruleViolations).contains(violationSnapShot) }
        }

        assertThat(shouldNotContainRuleViolation).allSatisfy { ruleViolation ->
            val matchingIssue = findMatchingIssue(issues) ?: fail("No matching issue found for path $path")
            val violationSnapShot = RuleViolationReport(ruleViolations = setOf(ruleViolation)).toSnapShots().first()
            assertThat(matchingIssue).anySatisfy { assertThat(it.ruleViolations).doesNotContain(violationSnapShot) }
        }
    }

    private fun assertTotalViolations(issues: List<Issue>) {
        if (totalViolations == null) return
        val matchingIssue = findMatchingIssue(issues) ?: fail("No matching issue found for path $path")
        assertThat(matchingIssue.sumOf { it.ruleViolations.size }).isEqualTo(totalViolations)
    }

    private fun assertSeverity(issues: List<Issue>) {
        val matchingIssue = findMatchingIssue(issues) ?: fail("No matching issue found for path $path")
        assertThat(matchingIssue).allSatisfy { assertThat(it.severity).isEqualTo(severity) }
    }

    private fun findMatchingIssue(issues: List<Issue>): List<Issue>? {
        return issues.filter { issue ->
            issue.breadCrumb == path || issue.path.joinToString(prefix = "/", separator = "/") == path || (path == null && issue.path.isEmpty())
        }.takeUnless { it.isEmpty() }
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
            logger.boundary()
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

    @ParameterizedTest
    @MethodSource("objectRuleViolationTestCases")
    fun object_rule_violations(case: RuleViolationCase, info: TestInfo) = case.assertViolation()

    @ParameterizedTest
    @MethodSource("arrayRuleViolationTestCases")
    fun array_rule_violations(case: RuleViolationCase, info: TestInfo) = case.assertViolation()

    @ParameterizedTest
    @MethodSource("allOfRuleViolationTestCases")
    fun all_of_rule_violations(case: RuleViolationCase, info: TestInfo) = case.assertViolation()

    @ParameterizedTest
    @MethodSource("anyOfRuleViolationTestCases")
    fun any_of_rule_violations(case: RuleViolationCase, info: TestInfo) = case.assertViolation()

    @ParameterizedTest
    @MethodSource("oneOfRuleViolationTestCases")
    fun one_of_rule_violations(case: RuleViolationCase, info: TestInfo) = case.assertViolation()

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
                violationTestCase(name = "Value Mismatch with Const") {
                    withPattern(name = "TEST") {
                        put("const", "This-Should-Be-A-Date")
                    }
                    forPattern(name = "TEST") {
                        withValue("This-Is-Not-A-Date")
                        expect {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.VALUE_MISMATCH)
                        }
                    }
                },
                violationTestCase(name = "Value Mismatch with Enum") {
                    withPattern(name = "TEST") {
                        put("type", "string")
                        put("enum", listOf("This-Should-Be-A-Date", "This-Should-Be-A-Number"))
                    }
                    forPattern(name = "TEST") {
                        withValue("This-Is-Not-A-Date-And-Not-A-Number")
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
                        put("allOf", listOf(mapOf("\$ref" to "#/components/schemas/Base")))
                        put("properties", mapOf("whiskers" to mapOf("type" to "integer")))
                        put("required", listOf("whiskers"))
                    }
                    withPattern(name = "Dog") {
                        put("type", "object")
                        put("allOf", listOf(mapOf("\$ref" to "#/components/schemas/Base")))
                        put("properties", mapOf("bark" to mapOf("type" to "boolean")))
                        put("required", listOf("bark"))
                    }
                    withPattern(name = "Animal") {
                        put("oneOf", listOf(mapOf("\$ref" to "#/components/schemas/Cat"), mapOf("\$ref" to "#/components/schemas/Dog")))
                        put("discriminator", mapOf("propertyName" to "type", "mapping" to mapOf("Cat" to "#/components/schemas/Cat", "Dog" to "#/components/schemas/Dog")))
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
                        put("allOf", listOf(mapOf("\$ref" to "#/components/schemas/Base")))
                        put("properties", mapOf("whiskers" to mapOf("type" to "integer")))
                        put("required", listOf("whiskers"))
                    }
                    withPattern(name = "Dog") {
                        put("type", "object")
                        put("allOf", listOf(mapOf("\$ref" to "#/components/schemas/Base")))
                        put("properties", mapOf("bark" to mapOf("type" to "boolean")))
                        put("required", listOf("bark"))
                    }
                    withPattern(name = "Animal") {
                        put("oneOf", listOf(mapOf("\$ref" to "#/components/schemas/Cat"), mapOf("\$ref" to "#/components/schemas/Dog")))
                        put("discriminator", mapOf("propertyName" to "type", "mapping" to mapOf("Cat" to "#/components/schemas/Cat", "Dog" to "#/components/schemas/Dog")))
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
                        put("oneOf", listOf(mapOf("\$ref" to "#/components/schemas/Cat"), mapOf("\$ref" to "#/components/schemas/Dog")))
                        put("discriminator", mapOf("propertyName" to "type", "mapping" to mapOf("Cat" to "#/components/schemas/Cat", "Dog" to "#/components/schemas/Dog")))
                    }

                    forPattern(name = "Animal") {
                        withValue(mapOf("type" to "Cat", "beakLength" to 10))
                        expect(path = "/type") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.INVALID_DISCRIMINATOR_SETUP)
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
                            mapOf("\$ref" to "#/components/schemas/OptionA"),
                            mapOf("\$ref" to "#/components/schemas/OptionB")
                        ))
                    }

                    forPattern(name = "AnyOfPattern") {
                        withValue(mapOf("c" to 10))
                        expect(path = "/c") {
                            totalViolations(3)
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
                            mapOf("\$ref" to "#/components/schemas/OptionA"),
                            mapOf("\$ref" to "#/components/schemas/OptionB")
                        ))
                    }

                    forPattern(name = "AnyOfPattern") {
                        withValue(mapOf("common" to true))
                        expect(path = "/common") {
                            totalViolations(5)
                            toContainViolation(StandardRuleViolation.ANY_OF_NO_MATCHING_SCHEMA)
                        }
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun objectRuleViolationTestCases(): Stream<Arguments> {
            return listOf(
                violationTestCase(name = "Type Mismatch") {
                    withPattern(name = "TEST") {
                        put("type", "object")
                        put("properties", mapOf("child" to mapOf("type" to "object", "properties" to mapOf("age" to mapOf("type" to "number")))))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("child" to mapOf("age" to "not-a-number")))
                        expect(path = "/child/age") { toContainViolation(StandardRuleViolation.TYPE_MISMATCH) }
                    }
                },
                violationTestCase(name = "Value Mismatch") {
                    withPattern(name = "TEST") {
                        put("type", "object")
                        put("properties", mapOf("child" to mapOf("type" to "object", "properties" to mapOf("status" to mapOf("enum" to listOf("active", "inactive"))))))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("child" to mapOf("status" to "pending")))
                        expect(path = "/child/status") { toContainViolation(StandardRuleViolation.VALUE_MISMATCH) }
                    }
                },
                violationTestCase(name = "Constraint Violation") {
                    withPattern(name = "TEST") {
                        put("type", "object")
                        put("properties", mapOf("child" to mapOf("type" to "object", "properties" to mapOf("code" to mapOf("type" to "string", "minLength" to 5)))))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("child" to mapOf("code" to "123")))
                        expect(path = "/child/code") { toContainViolation(StandardRuleViolation.CONSTRAINT_VIOLATION) }
                    }
                },
                violationTestCase(name = "Required Property Missing") {
                    withPattern(name = "TEST") {
                        put("type", "object")
                        put("properties", mapOf("child" to mapOf("type" to "object", "required" to listOf("id"), "properties" to mapOf("id" to mapOf("type" to "string")))))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("child" to emptyMap<String, Any>()))
                        expect(path = "/child/id") { toContainViolation(StandardRuleViolation.REQUIRED_PROPERTY_MISSING) }
                    }
                },
                violationTestCase(name = "Optional Property Missing") {
                    withPattern(name = "TEST") {
                        put("type", "object")
                        put("properties", mapOf("child" to mapOf("type" to "object", "required" to emptyList<String>(), "properties" to mapOf("id" to mapOf("type" to "string")))))
                    }
                    forPattern(name = "TEST") {
                        withResolver { resolver -> resolver.withAllPatternsAsMandatory() }
                        withValue(mapOf("child" to emptyMap<String, Any>()))
                        expect(path = "/child/id") {
                            toHaveSeverity(IssueSeverity.WARNING)
                            toContainViolation(StandardRuleViolation.OPTIONAL_PROPERTY_MISSING)
                        }
                    }
                },
                violationTestCase(name = "Unknown Property") {
                    withPattern(name = "TEST") {
                        put("type", "object")
                        put("properties", mapOf("child" to mapOf("type" to "object", "additionalProperties" to false)))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("child" to mapOf("extra" to "val")))
                        expect(path = "/child/extra") { toContainViolation(StandardRuleViolation.UNKNOWN_PROPERTY) }
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun arrayRuleViolationTestCases(): Stream<Arguments> {
            return listOf(
                violationTestCase(name = "Type Mismatch") {
                    withPattern(name = "TEST") { put("type", "array"); put("items", mapOf("type" to "number")) }
                    forPattern(name = "TEST") {
                        withValue(listOf(1, "two"))
                        expect(path = "/1") { toContainViolation(StandardRuleViolation.TYPE_MISMATCH) }
                    }
                },
                violationTestCase(name = "Value Mismatch") {
                    withPattern(name = "TEST") { put("type", "array"); put("items", mapOf("const" to "Fixed")) }
                    forPattern(name = "TEST") {
                        withValue(listOf("Fixed", "Wrong"))
                        expect(path = "/1") { toContainViolation(StandardRuleViolation.VALUE_MISMATCH) }
                    }
                },
                violationTestCase(name = "Constraint Mismatch") {
                    withPattern(name = "TEST") { put("type", "array"); put("items", mapOf("type" to "integer", "exclusiveMinimum" to 100)) }
                    forPattern(name = "TEST") {
                        withValue(listOf(100))
                        expect(path = "/0") { toContainViolation(StandardRuleViolation.CONSTRAINT_VIOLATION) }
                    }
                },
                violationTestCase(name = "Missing Required Property") {
                    withPattern(name = "TEST") {
                        put("type", "array")
                        put("items", mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "string")), "required" to listOf("id")))
                    }
                    forPattern(name = "TEST") {
                        withValue(listOf(mapOf("id" to "001"), emptyMap<String, Any>()))
                        expect(path = "/1/id") { toContainViolation(StandardRuleViolation.REQUIRED_PROPERTY_MISSING) }
                    }
                },
                violationTestCase(name = "Optional Missing") {
                    withPattern(name = "TEST") {
                        put("type", "array")
                        put("items", mapOf("type" to "object", "properties" to mapOf("tag" to mapOf("type" to "string"))))
                    }
                    forPattern(name = "TEST") {
                        withValue(listOf(emptyMap<String, Any>()))
                        withResolver { it.withAllPatternsAsMandatory() }
                        expect(path = "/0/tag") {
                            toHaveSeverity(IssueSeverity.WARNING)
                            toContainViolation(StandardRuleViolation.OPTIONAL_PROPERTY_MISSING)
                        }
                    }
                },
                violationTestCase(name = "Unknown Property") {
                    withPattern(name = "TEST") {
                        put("type", "array")
                        put("items", mapOf("type" to "object", "additionalProperties" to false))
                    }
                    forPattern(name = "TEST") {
                        withValue(listOf(mapOf("forbidden" to true)))
                        expect(path = "/0/forbidden") { toContainViolation(StandardRuleViolation.UNKNOWN_PROPERTY) }
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun allOfRuleViolationTestCases(): Stream<Arguments> {
            return listOf(
                violationTestCase(name = "Type Mismatch") {
                    withPattern(name = "TEST") {
                        put("allOf", listOf(
                            mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "number"))),
                            mapOf("type" to "object", "properties" to mapOf("name" to mapOf("type" to "string")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("id" to "not-a-number"))
                        expect(path = "/id") { toContainViolation(StandardRuleViolation.TYPE_MISMATCH) }
                    }
                },
                violationTestCase(name = "Value Mismatch") {
                    withPattern(name = "TEST") {
                        put("allOf", listOf(
                            mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "number"))),
                            mapOf("type" to "object", "properties" to mapOf("name" to mapOf("type" to "string", "enum" to listOf("A", "B")))
                        )))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("name" to "C"))
                        expect(path = "/name") { toContainViolation(StandardRuleViolation.VALUE_MISMATCH) }
                    }
                },
                violationTestCase(name = "Constraint Violation") {
                    withPattern(name = "TEST") {
                        put("allOf", listOf(
                            mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "number"))),
                            mapOf("type" to "object", "properties" to mapOf("name" to mapOf("type" to "string", "minLength" to 5))
                        )))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("name" to "John"))
                        expect(path ="/name") { toContainViolation(StandardRuleViolation.CONSTRAINT_VIOLATION) }
                    }
                },
                violationTestCase(name = "Missing Required Property") {
                    withPattern(name = "TEST") {
                        put("allOf", listOf(
                            mapOf("type" to "object", "required" to listOf("name"), "properties" to mapOf("name" to mapOf("type" to "string"))),
                            mapOf("type" to "object", "required" to listOf("age"), "properties" to mapOf("age" to mapOf("type" to "number")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("name" to "John"))
                        expect(path = "/age") { toContainViolation(StandardRuleViolation.REQUIRED_PROPERTY_MISSING) }
                    }
                },
                violationTestCase(name = "Missing Optional Property") {
                    withPattern(name = "TEST") {
                        put("allOf", listOf(
                            mapOf("type" to "object", "required" to listOf("name"), "properties" to mapOf("name" to mapOf("type" to "string"))),
                            mapOf("type" to "object", "properties" to mapOf("age" to mapOf("type" to "number")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withResolver { it.withAllPatternsAsMandatory() }
                        withValue(mapOf("name" to "John"))
                        expect(path = "/age") {
                            toHaveSeverity(IssueSeverity.WARNING)
                            toContainViolation(StandardRuleViolation.OPTIONAL_PROPERTY_MISSING)
                        }
                    }
                },
                violationTestCase(name = "Unknown Property") {
                    withPattern(name = "TEST") {
                        put("allOf", listOf(mapOf("properties" to mapOf("id" to mapOf("type" to "string")))))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("id" to "123", "extra" to "bad"))
                        expect(path = "/extra") { toContainViolation(StandardRuleViolation.UNKNOWN_PROPERTY) }
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun anyOfRuleViolationTestCases(): Stream<Arguments> {
            return listOf(
                violationTestCase(name = "Type Mismatch") {
                    withPattern(name = "TEST") {
                        put("anyOf", listOf(
                            mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "number"))),
                            mapOf("type" to "object", "properties" to mapOf("name" to mapOf("type" to "string")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("id" to "not-a-number"))
                        expect(path = "/id") {
                            toContainViolation(StandardRuleViolation.TYPE_MISMATCH)
                            toContainViolation(StandardRuleViolation.ANY_OF_NO_MATCHING_SCHEMA)
                        }
                    }
                },
                violationTestCase(name = "Value Mismatch") {
                    withPattern(name = "TEST") {
                        put("anyOf", listOf(
                            mapOf("type" to "object", "properties" to mapOf("status" to mapOf("enum" to listOf("A", "B")))),
                            mapOf("type" to "object", "properties" to mapOf("code" to mapOf("type" to "number")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("status" to "C"))
                        expect(path = "/status") {
                            toContainViolation(StandardRuleViolation.VALUE_MISMATCH)
                            toContainViolation(StandardRuleViolation.ANY_OF_NO_MATCHING_SCHEMA)
                        }
                    }
                },
                violationTestCase(name = "Constraint Violation") {
                    withPattern(name = "TEST") {
                        put("anyOf", listOf(
                            mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "string", "minLength" to 5))),
                            mapOf("type" to "object", "properties" to mapOf("age" to mapOf("type" to "number")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("id" to "123"))
                        expect(path = "/id") {
                            toContainViolation(StandardRuleViolation.CONSTRAINT_VIOLATION)
                            toContainViolation(StandardRuleViolation.ANY_OF_NO_MATCHING_SCHEMA)
                        }
                    }
                },
                violationTestCase(name = "Missing Required Property") {
                    withPattern(name = "TEST") {
                        put("anyOf", listOf(
                            mapOf("type" to "object", "required" to listOf("id"), "properties" to mapOf("id" to mapOf("type" to "string"))),
                            mapOf("type" to "object", "required" to listOf("name"), "properties" to mapOf("name" to mapOf("type" to "string")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withValue(emptyMap<String, Any>())
                        expect(path = "/id") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.REQUIRED_PROPERTY_MISSING)
                        }
                        expect(path = "/name") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.REQUIRED_PROPERTY_MISSING)
                        }
                    }
                },
                violationTestCase(name = "Missing Optional Property") {
                    withPattern(name = "TEST") {
                        put("anyOf", listOf(
                            mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "string")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withResolver { it.withAllPatternsAsMandatory() }
                        withValue(emptyMap<String, Any>())
                        expect(path = "/id") {
                            toHaveSeverity(IssueSeverity.WARNING)
                            toContainViolation(StandardRuleViolation.OPTIONAL_PROPERTY_MISSING)
                        }
                    }
                },
                violationTestCase(name = "Unknown Property") {
                    withPattern(name = "TEST") {
                        put("anyOf", listOf(mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "string")))))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("id" to "123", "extra" to "val"))
                        expect(path = "/extra") {
                            totalViolations(1)
                            toContainViolation(StandardRuleViolation.ANY_OF_UNKNOWN_KEY)
                        }
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun oneOfRuleViolationTestCases(): Stream<Arguments> {
            return listOf(
                violationTestCase(name = "Type Mismatch") {
                    withPattern(name = "TEST") {
                        put("oneOf", listOf(
                            mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "number"))),
                            mapOf("type" to "object", "properties" to mapOf("name" to mapOf("type" to "string")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("id" to "not-a-number"))
                        expect(path = "/id") { toContainViolation(StandardRuleViolation.TYPE_MISMATCH) }
                    }
                },
                violationTestCase(name = "Value Mismatch") {
                    withPattern(name = "TEST") {
                        put("oneOf", listOf(
                            mapOf("type" to "object", "properties" to mapOf("status" to mapOf("enum" to listOf("A", "B")))),
                            mapOf("type" to "object", "properties" to mapOf("code" to mapOf("type" to "number")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("status" to "C"))
                        expect(path = "/status") { toContainViolation(StandardRuleViolation.VALUE_MISMATCH) }
                    }
                },
                violationTestCase(name = "Constraint Violation") {
                    withPattern(name = "TEST") {
                        put("oneOf", listOf(
                            mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "string", "minLength" to 5))),
                            mapOf("type" to "object", "properties" to mapOf("age" to mapOf("type" to "number")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("id" to "123"))
                        expect(path = "/id") { toContainViolation(StandardRuleViolation.CONSTRAINT_VIOLATION) }
                    }
                },
                violationTestCase(name = "Missing Required Property") {
                    withPattern(name = "TEST") {
                        put("oneOf", listOf(
                            mapOf("type" to "object", "required" to listOf("id"), "properties" to mapOf("id" to mapOf("type" to "string"))),
                            mapOf("type" to "object", "required" to listOf("name"), "properties" to mapOf("name" to mapOf("type" to "string")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withValue(emptyMap<String, Any>())
                        expect(path = "/id") { toContainViolation(StandardRuleViolation.REQUIRED_PROPERTY_MISSING) }
                        expect(path = "/name") { toContainViolation(StandardRuleViolation.REQUIRED_PROPERTY_MISSING) }
                    }
                },
                violationTestCase(name = "Missing Optional Property") {
                    withPattern(name = "TEST") {
                        put("oneOf", listOf(mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "string")))))
                    }
                    forPattern(name = "TEST") {
                        withResolver { it.withAllPatternsAsMandatory() }
                        withValue(emptyMap<String, Any>())
                        expect(path = "/id") {
                            toHaveSeverity(IssueSeverity.WARNING)
                            toContainViolation(StandardRuleViolation.OPTIONAL_PROPERTY_MISSING)
                        }
                    }
                },
                violationTestCase(name = "Multiple Matches") {
                    withPattern(name = "TEST") {
                        put("oneOf", listOf(
                            mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "string"))),
                            mapOf("type" to "object", "properties" to mapOf("name" to mapOf("type" to "string")))
                        ))
                    }
                    forPattern(name = "TEST") {
                        withValue(mapOf("id" to "123", "name" to "John"))
                        expect(path = "/id") { toContainViolation(StandardRuleViolation.UNKNOWN_PROPERTY) }
                        expect(path = "/name") { toContainViolation(StandardRuleViolation.UNKNOWN_PROPERTY) }
                    }
                }
            ).flatten().stream()
        }
    }
}
