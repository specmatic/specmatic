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
    fun `encompasses succeeds when my choice and other choice have the same branches`() {
        val myChoice = choiceGroup("CustomerNumber", "LoginId")
        val otherChoice = choiceGroup("CustomerNumber", "LoginId")

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `encompasses fails when a branch is added to the other choice`() {
        val myChoice = choiceGroup("CustomerNumber", "LoginId")
        val otherChoice = choiceGroup("CustomerNumber", "LoginId", "Email")

        assertFailure(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `encompasses succeeds when a branch is added to my choice`() {
        val myChoice = choiceGroup("CustomerNumber", "LoginId", "Email")
        val otherChoice = choiceGroup("CustomerNumber", "LoginId")

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `encompasses succeeds when a branch is removed from the other choice`() {
        val myChoice = choiceGroup("CustomerNumber", "LoginId")
        val otherChoice = choiceGroup("CustomerNumber")

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `encompasses fails when a branch is removed from my choice`() {
        val myChoice = choiceGroup("CustomerNumber")
        val otherChoice = choiceGroup("CustomerNumber", "LoginId")

        assertFailure(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `encompasses fails for an other branch beyond max test request combinations`() {
        val boundedResolver = resolver.copy(maxTestRequestCombinations = 1)
        val myChoice = choiceGroup("A")
        val otherChoice = choiceGroup("A", "B")

        assertFailure(myChoice.encompasses(otherChoice, boundedResolver, boundedResolver))
    }

    @Test
    fun `optional my choice encompasses required other choice`() {
        val myChoice = choiceGroup("A", minOccurs = 0, maxOccurs = 1)
        val otherChoice = choiceGroup("A", minOccurs = 1, maxOccurs = 1)

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `required my choice does not encompass optional other choice`() {
        val myChoice = choiceGroup("A", minOccurs = 1, maxOccurs = 1)
        val otherChoice = choiceGroup("A", minOccurs = 0, maxOccurs = 1)

        assertFailure(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `my choice with wider max encompasses other choice with narrower max`() {
        val myChoice = choiceGroup("A", minOccurs = 1, maxOccurs = 2)
        val otherChoice = choiceGroup("A", minOccurs = 1, maxOccurs = 1)

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `my choice with narrower max does not encompass other choice with wider max`() {
        val myChoice = choiceGroup("A", minOccurs = 1, maxOccurs = 1)
        val otherChoice = choiceGroup("A", minOccurs = 1, maxOccurs = 2)

        assertFailure(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `unbounded my choice encompasses bounded other choice`() {
        val myChoice = choiceGroup("A", minOccurs = 1, maxOccurs = null)
        val otherChoice = choiceGroup("A", minOccurs = 1, maxOccurs = 5)

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `bounded my choice does not encompass unbounded other choice`() {
        val myChoice = choiceGroup("A", minOccurs = 1, maxOccurs = 5)
        val otherChoice = choiceGroup("A", minOccurs = 1, maxOccurs = null)

        assertFailure(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `encompasses succeeds for a matching multi node branch`() {
        val myChoice = choiceGroupOfBranches(
            listOf("Common", "BranchB"),
            listOf("Common", "BranchC")
        )
        val otherChoice = choiceGroupOfBranches(listOf("Common", "BranchC"))

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `encompasses fails for an unknown multi node branch`() {
        val myChoice = choiceGroupOfBranches(
            listOf("Common", "BranchB"),
            listOf("Common", "BranchC")
        )
        val otherChoice = choiceGroupOfBranches(listOf("Common", "BranchD"))

        assertFailure(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `encompasses succeeds for a nested choice subset`() {
        val myChoice = XMLChoiceGroupPattern(
            choices = listOf(
                listOf(xmlElement("DirectId")),
                listOf(choiceGroup("NestedA", "NestedB"))
            )
        )
        val otherChoice = XMLChoiceGroupPattern(
            choices = listOf(listOf(choiceGroup("NestedA")))
        )

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `encompasses fails for a nested branch added to the other choice`() {
        val myChoice = XMLChoiceGroupPattern(
            choices = listOf(
                listOf(xmlElement("DirectId")),
                listOf(choiceGroup("NestedA", "NestedB"))
            )
        )
        val otherChoice = XMLChoiceGroupPattern(
            choices = listOf(listOf(choiceGroup("NestedA", "NestedC")))
        )

        assertFailure(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `normal choice encompasses a concrete sequence within range`() {
        val myChoice = choiceGroup("A", "B", minOccurs = 2, maxOccurs = 2)
        val otherChoice = concreteChoiceSequence("A", "B")

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `concrete sequence encompasses identical concrete sequence`() {
        val myChoice = concreteChoiceSequence("A", "B")
        val otherChoice = concreteChoiceSequence("A", "B")

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `concrete sequence rejects different order`() {
        val myChoice = concreteChoiceSequence("A", "B")
        val otherChoice = concreteChoiceSequence("B", "A")

        assertFailure(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `concrete sequence does not encompass normal choice permutations`() {
        val myChoice = concreteChoiceSequence("A", "B")
        val otherChoice = choiceGroup("A", "B", minOccurs = 2, maxOccurs = 2)

        assertFailure(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `choice encompasses a matching single XML pattern`() {
        val myChoice = choiceGroup("A", "B")
        val otherPattern = xmlElement("A")

        assertSuccess(myChoice.encompasses(otherPattern, resolver, resolver))
    }

    @Test
    fun `choice rejects an unknown single XML pattern`() {
        val myChoice = choiceGroup("A", "B")
        val otherPattern = xmlElement("C")

        assertFailure(myChoice.encompasses(otherPattern, resolver, resolver))
    }

    private fun choiceGroup(
        vararg branchNames: String,
        minOccurs: Int = 1,
        maxOccurs: Int? = 1
    ): XMLChoiceGroupPattern {
        return XMLChoiceGroupPattern(
            choices = branchNames.map { branchName ->
                listOf(xmlElement(branchName))
            },
            minOccurs = minOccurs,
            maxOccurs = maxOccurs
        )
    }

    private fun choiceGroupOfBranches(vararg branches: List<String>): XMLChoiceGroupPattern {
        return XMLChoiceGroupPattern(
            choices = branches.map { branch ->
                branch.map(::xmlElement)
            }
        )
    }

    private fun concreteChoiceSequence(vararg branchNames: String): XMLChoiceGroupPattern {
        val branchPatterns = branchNames.map(::xmlElement)

        return XMLChoiceGroupPattern(
            choices = branchNames.distinct().map { branchName ->
                listOf(xmlElement(branchName))
            },
            concreteSequence = branchPatterns.map { listOf(it) }
        )
    }

    private fun xmlElement(name: String): XMLPattern = XMLPattern("<$name>(string)</$name>")

    private fun assertSuccess(result: Result) {
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    private fun assertFailure(result: Result) {
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }
}
