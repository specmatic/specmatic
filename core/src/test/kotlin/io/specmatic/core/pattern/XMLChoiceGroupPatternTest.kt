package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.toXMLNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class XMLChoiceGroupPatternTest {
    private val resolver = Resolver()

    @Test
    fun `matches accepts mixed branches across repeated choice occurrences`() {
        val pattern = XMLChoiceGroupPattern(
            choices = listOf(
                listOf(XMLPattern("<A>(string)</A>")),
                listOf(XMLPattern("<B>(string)</B>"))
            ),
            minOccurs = 1,
            maxOccurs = 2
        )

        val result = pattern.matches(listOf(toXMLNode("<A>one</A>"), toXMLNode("<B>two</B>")), resolver)

        assertThat(result.result).isInstanceOf(io.specmatic.core.Result.Success::class.java)
        assertThat(result.remainder).isEmpty()
    }

    @Test
    fun `matches rejects more than one occurrence when max occurs is one`() {
        val pattern = XMLChoiceGroupPattern(
            choices = listOf(
                listOf(XMLPattern("<A>(string)</A>")),
                listOf(XMLPattern("<B>(string)</B>"))
            ),
            minOccurs = 1,
            maxOccurs = 1
        )

        val result = pattern.matches(listOf(toXMLNode("<A>one</A>"), toXMLNode("<B>two</B>")), resolver)

        assertThat(result.result).isInstanceOf(io.specmatic.core.Result.Success::class.java)
        assertThat(result.remainder).hasSize(1)
    }

    @Test
    fun `new based on only emits valid repeated occurrence sequences`() {
        val pattern = XMLChoiceGroupPattern(
            choices = listOf(
                listOf(XMLPattern("<A>(string)</A>")),
                listOf(XMLPattern("<B>(string)</B>"))
            ),
            minOccurs = 1,
            maxOccurs = 2
        )

        val generated = pattern.newBasedOn(resolver).map { it as XMLChoiceGroupPattern }.toList()

        assertThat(generated).hasSize(6)
        assertThat(generated.map { it.generate(resolver).toStringLiteral() }).allSatisfy { xml ->
            val aCount = countOccurrences(xml, "<A>")
            val bCount = countOccurrences(xml, "<B>")
            assertThat(aCount + bCount).isBetween(1, 2)
        }
    }

    private fun countOccurrences(text: String, token: String): Int {
        return text.windowed(token.length, 1).count { it == token }
    }
}
