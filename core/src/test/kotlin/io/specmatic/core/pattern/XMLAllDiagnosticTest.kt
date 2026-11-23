package io.specmatic.core.pattern

import io.specmatic.core.*
import io.specmatic.core.value.Value
import io.specmatic.core.value.toXMLNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class XMLAllDiagnosticTest {
    @Test
    fun `fallback preserves the original retained failure including source rule and nested path`() {
        val location = SourceLocation("schema.xsd", 7, 9, "/schema/Entry/Code")
        val original = Result.Failure("Code exceeds its constraint", ruleViolation = StandardRuleViolation.CONSTRAINT_VIOLATION)
            .breadCrumb("Code", location).breadCrumb("Entry")
        val values = values("<Entry>invalid</Entry><Tail>ok</Tail>")
        val optional = retained(original)
        for (members in listOf(listOf(optional, tail), listOf(tail, optional))) {
            val matched = XMLAllPattern(members).matches(values, Resolver())
            assertThat(matched.result).isSameAs(original)
            assertThat(matched.remainder).isSameAs(values)
            assertThat((matched.result as Result.Failure).toMatchFailureDetailList().single().sourceLocation).isEqualTo(location)
            assertThat(matched.provisionalError?.result).isSameAs(original)
        }
    }

    @Test
    fun `equally relevant failures retain declaration order including required legacy members`() {
        val values = values("<Entry>invalid</Entry><Tail>ok</Tail>")
        val first = Result.Failure("first").breadCrumb("Entry")
        val second = Result.Failure("second").breadCrumb("Entry")
        val a = retained(first)
        val b = retained(second)
        for ((members, expected) in listOf(listOf(a, b, tail) to first, listOf(b, a, tail) to second))
            assertThat(XMLAllPattern(members).matches(values, Resolver()).result).isSameAs(expected)
        val required = XMLPattern("<Entry>(number)</Entry>")
        val requiredFailure = required.matches(values, Resolver()).result
        val actual = XMLAllPattern(listOf(required, a, tail)).matches(values, Resolver()).result
        assertThat(actual).isEqualTo(requiredFailure)
    }

    @Test
    fun `failed consuming continuation retains precedence over a local retained failure`() {
        val values = values("<Entry>invalid</Entry><Tail>invalid</Tail>")
        val original = Result.Failure("local retained failure").breadCrumb("Entry")
        val matcher = XMLAllPattern(listOf(retained(original), XMLPattern("<Entry>(string)</Entry>"), XMLPattern("<Tail>(number)</Tail>")))
        val result = matcher.matches(values, Resolver())
        assertThat(result.result.reportString()).contains(">> Tail").doesNotContain("local retained failure")
        assertThat(result.remainder).isSameAs(values)
    }

    @Test
    fun `successful consuming alternative wins and preserves the unmatched remainder`() {
        val values = values("<Entry>invalid</Entry><Tail>ok</Tail><After/>")
        val matcher = XMLAllPattern(listOf(retained(Result.Failure("speculative")), XMLPattern("<Entry>(string)</Entry>"), tail))
        val result = matcher.matches(values, Resolver())
        assertThat(result.result.isSuccess()).isTrue()
        assertThat(result.remainder).containsExactly(values.last())
    }

    @Test
    fun `all nullable members retain success and their provisional failure`() {
        val values = values("<Entry>invalid</Entry>")
        val original = Result.Failure("retained")
        val result = XMLAllPattern(listOf(retained(original))).matches(values, Resolver())
        assertThat(result.result.isSuccess()).isTrue()
        assertThat(result.remainder).isSameAs(values)
        assertThat(result.provisionalError?.result).isSameAs(original)
    }

    @Test
    fun `plain ordered node lists retain known content failures and genuine optional omission`() {
        for (flat in listOf(false, true)) for (optional in listOf(false, true)) {
            val member = if (flat) "Code" else "Entry"
            val content = if (flat) "(number)" else "<Code>(number)</Code>"
            val pattern = XMLPattern("<Root><$member${if (optional) " specmatic_occurs=\"optional\"" else ""}>$content</$member><Tail>(string)</Tail></Root>")
            val invalid = if (flat) "<Code>invalid</Code>" else "<Entry><Code>invalid</Code></Entry>"
            val result = pattern.matches(toXMLNode("<Root>$invalid<Tail>ok</Tail></Root>"), Resolver())
            assertThat(result.reportString()).contains(if (flat) ">> Root.Code" else ">> Root.Entry.Code")
            assertThat(pattern.matches(toXMLNode("<Root><Tail>ok</Tail></Root>"), Resolver()).isSuccess()).isEqualTo(optional)
        }
    }

    @Test
    fun `namespace qualification distinguishes unconstrained empty and explicit requirements`() {
        for ((required, actual, qualifies) in listOf(
            Triple(null, "urn:any", true), Triple("", "", true),
            Triple("", "urn:any", false), Triple("urn:expected", "urn:expected", true))) {
            val member = XMLPattern("<Entry>(number)</Entry>").let {
                it.copy(pattern = it.pattern.copy(requiredNamespaceUri = required, occurrences = XMLOccurrenceRange(0, 1)))
            }
            val values = values("""<Entry xmlns="$actual">invalid</Entry><Tail>ok</Tail>""")
            val result = XMLAllPattern(listOf(member, tail)).matches(values, Resolver())
            assertThat(result.result.isSuccess()).isFalse()
            assertThat(result.result.reportString()).contains(if (qualifies) ">> Entry" else ">> Tail")
            assertThat(result.remainder).isSameAs(values)
        }
    }

    private fun retained(failure: Result.Failure): Pattern {
        val identity = XMLPattern("<Entry>(number)</Entry>")
        return object : Pattern by identity {
            override fun matches(sampleData: List<Value>, resolver: Resolver): ConsumeResult<Value, Value> =
                if (sampleData.isEmpty()) ConsumeResult(Result.Success(), sampleData)
                else ConsumeResult(Result.Success(), sampleData, ProvisionalError(failure, identity, sampleData.first()))
        }
    }

    private fun values(children: String): List<Value> = toXMLNode("<Root>$children</Root>").childNodes
    private val tail = XMLPattern("<Tail>(string)</Tail>")
}
