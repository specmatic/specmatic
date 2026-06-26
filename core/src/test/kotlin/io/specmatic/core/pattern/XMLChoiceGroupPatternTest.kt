package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
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
    fun `matches accepts a later branch when the first branch fails`() {
        val pattern = XMLChoiceGroupPattern(
            choices = listOf(
                listOf(XMLPattern("<A>(string)</A>")),
                listOf(XMLPattern("<B>(string)</B>"))
            )
        )

        val result = pattern.matches(listOf(toXMLNode("<B>two</B>")), resolver)

        assertThat(result.result).isInstanceOf(io.specmatic.core.Result.Success::class.java)
        assertThat(result.remainder).isEmpty()
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
        val sequences = generated.map { variant ->
            variant.concreteSequence.orEmpty().map { occurrence ->
                ((occurrence.single() as XMLPattern).pattern.name).substringAfter(":")
            }
        }

        assertThat(generated).hasSize(6)
        assertThat(sequences).containsExactlyInAnyOrder(
            listOf("A"),
            listOf("B"),
            listOf("A", "A"),
            listOf("A", "B"),
            listOf("B", "A"),
            listOf("B", "B"),
        )
    }

    @Test
    fun `encompasses treats a new choice branch as incompatible and a removed branch as compatible`() {
        val oldChoice = choiceGroup("CustomerNumber", "LoginId")
        val choiceWithAddedBranch = choiceGroup("CustomerNumber", "LoginId", "Email")
        val choiceWithRemovedBranch = choiceGroup("CustomerNumber")

        assertThat(oldChoice.encompasses(oldChoice, resolver, resolver)).isInstanceOf(Result.Success::class.java)
        assertThat(oldChoice.encompasses(choiceWithAddedBranch, resolver, resolver)).isInstanceOf(Result.Failure::class.java)
        assertThat(oldChoice.encompasses(choiceWithRemovedBranch, resolver, resolver)).isInstanceOf(Result.Success::class.java)
    }

    private fun choiceGroup(vararg branchNames: String): XMLChoiceGroupPattern {
        return XMLChoiceGroupPattern(
            choices = branchNames.map { branchName ->
                listOf(XMLPattern("<$branchName>(string)</$branchName>"))
            }
        )
    }
}
