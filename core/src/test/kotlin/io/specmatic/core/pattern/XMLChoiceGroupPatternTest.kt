package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.XMLNode
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
    fun `new based on materializes bounded repeated occurrence sequences`() {
        val pattern = XMLChoiceGroupPattern(
            choices = listOf(
                listOf(XMLPattern("<A>(string)</A>")),
                listOf(XMLPattern("<B>(string)</B>"))
            ),
            minOccurs = 1,
            maxOccurs = 2
        )

        val generated = pattern.newBasedOn(resolver).map { it as XMLSequencePattern }.toList()

        assertThat(generated.map(::choiceNames)).containsExactlyInAnyOrder(
            listOf("A"),
            listOf("B"),
            listOf("A", "B"),
        )
        assertThat(generated).allSatisfy { variant ->
            assertThat(variant.members).isNotEmpty()
        }
    }

    @Test
    fun `new based on materializes one single occurrence variant per branch`() {
        val generated = choiceGroup("A", "B", "C").newBasedOn(resolver).map { it as XMLSequencePattern }.toList()

        assertThat(generated.map(::choiceNames)).containsExactlyInAnyOrder(
            listOf("A"),
            listOf("B"),
            listOf("C")
        )
    }

    @Test
    fun `new based on materializes optional absence and single branch variants`() {
        val generated = choiceGroup("A", "B", minOccurs = 0, maxOccurs = 1)
            .newBasedOn(resolver)
            .map { it as XMLSequencePattern }
            .toList()

        assertThat(generated.map(::choiceNames)).containsExactlyInAnyOrder(
            emptyList(),
            listOf("A"),
            listOf("B")
        )
    }

    @Test
    fun `new based on cycles declaration order when max exceeds branch count`() {
        val generated = choiceGroup("A", "B", minOccurs = 0, maxOccurs = 3)
            .newBasedOn(resolver)
            .map { it as XMLSequencePattern }
            .toList()

        assertThat(generated.map(::choiceNames)).containsExactlyInAnyOrder(
            emptyList(),
            listOf("A", "B", "A")
        )
    }

    @Test
    fun `new based on includes exact min and max representatives`() {
        val generated = choiceGroup("A", "B", minOccurs = 2, maxOccurs = 4)
            .newBasedOn(resolver)
            .map { it as XMLSequencePattern }
            .toList()

        assertThat(generated.map(::choiceNames)).containsExactlyInAnyOrder(
            listOf("A", "B"),
            listOf("A", "B", "A", "B")
        )
    }

    @Test
    fun `new based on generates combinations when count does not exceed branch count`() {
        val generated = choiceGroup("A", "B", "C", minOccurs = 1, maxOccurs = 2)
            .newBasedOn(resolver)
            .map { it as XMLSequencePattern }
            .toList()

        assertThat(generated.map(::choiceNames)).containsExactlyInAnyOrder(
            listOf("A"),
            listOf("B"),
            listOf("C"),
            listOf("A", "B"),
            listOf("A", "C"),
            listOf("B", "C")
        )
    }

    @Test
    fun `new based on preserves multi node branch as one choice occurrence`() {
        val generated = choiceGroupOfBranches(listOf("Common", "BranchB"), listOf("Common", "BranchC"))
            .newBasedOn(resolver)
            .map { it as XMLSequencePattern }
            .toList()

        assertThat(generated.map(::choiceNames)).containsExactlyInAnyOrder(
            listOf("Common", "BranchB"),
            listOf("Common", "BranchC")
        )
    }

    @Test
    fun `new based on preserves repeated multi node branch grouping`() {
        val generated = choiceGroupOfBranches(
            listOf("Common", "BranchB"),
            listOf("Common", "BranchC"),
            minOccurs = 2,
            maxOccurs = 2
        ).newBasedOn(resolver).map { it as XMLSequencePattern }.toList()

        assertThat(generated.map(::choiceNames)).containsExactlyInAnyOrder(
            listOf("Common", "BranchB", "Common", "BranchC")
        )
    }

    @Test
    fun `materialized choice generates each selected occurrence exactly once`() {
        val materialized = choiceGroup("A", "B", minOccurs = 2, maxOccurs = 2)
            .newBasedOn(resolver)
            .map { it as XMLSequencePattern }
            .single()

        assertThat(generatedNodeNames(materialized)).containsExactly("A", "B")
    }

    @Test
    fun `new based on materializes nested choice branches recursively`() {
        val generated = XMLChoiceGroupPattern(
            choices = listOf(
                listOf(xmlElement("DirectId")),
                listOf(choiceGroup("NestedA", "NestedB"))
            )
        ).newBasedOn(resolver).map { it as XMLSequencePattern }.toList()

        assertThat(generated.map(::expandedChoiceNames)).containsExactlyInAnyOrder(
            listOf("DirectId"),
            listOf("NestedA"),
            listOf("NestedB")
        )
    }

    @Test
    fun `row based new based on preserves example values in materialized choices`() {
        val materialized = choiceGroup("A", "B")
            .newBasedOn(Row(listOf("A"), listOf("example value")), resolver)
            .map { it.value as XMLSequencePattern }
            .first { choiceNames(it) == listOf("A") }

        assertThat(generatedNodeNames(materialized)).containsExactly("A")
        assertThat(materialized.generate(resolver).toStringLiteral()).contains("<A>example value</A>")
    }

    @Test
    fun `generate omits optional choice when optional generation says no`() {
        val generated = choiceGroup("A", "B", minOccurs = 0, maxOccurs = 1).generate(
            resolver,
            FixedXMLChoiceGenerationDecisions(includeOptional = false)
        ) as XMLNode

        assertThat(generated.childNodes).isEmpty()
    }

    @Test
    fun `generate includes one optional choice selected by generation decisions`() {
        val generated = choiceGroup("A", "B", minOccurs = 0, maxOccurs = 3).generate(
            resolver,
            FixedXMLChoiceGenerationDecisions(includeOptional = true, branchIndex = 1)
        ) as XMLNode

        assertThat(generatedNodeNames(generated)).containsExactly("B")
    }

    @Test
    fun `generate uses minimum required occurrences for repeated choices`() {
        val generated = choiceGroup("A", "B", minOccurs = 2, maxOccurs = 4).generate(
            resolver,
            FixedXMLChoiceGenerationDecisions(branchIndex = 1)
        ) as XMLNode

        assertThat(generatedNodeNames(generated)).containsExactly("B", "B")
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
    fun `normal choice encompasses a materialized choice within range`() {
        val myChoice = choiceGroup("A", "B", minOccurs = 2, maxOccurs = 2)
        val otherChoice = materializedChoice("A", "B")

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `materialized choice encompasses identical materialized choice`() {
        val myChoice = materializedChoice("A", "B")
        val otherChoice = materializedChoice("A", "B")

        assertSuccess(myChoice.encompasses(otherChoice, resolver, resolver))
    }

    @Test
    fun `materialized sequence does not encompass repeated choice schema`() {
        val myChoice = materializedChoice("A", "B")
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

    private fun choiceGroupOfBranches(
        vararg branches: List<String>,
        minOccurs: Int = 1,
        maxOccurs: Int? = 1
    ): XMLChoiceGroupPattern {
        return XMLChoiceGroupPattern(
            choices = branches.map { branch -> branch.map(::xmlElement) },
            minOccurs = minOccurs,
            maxOccurs = maxOccurs
        )
    }

    private fun materializedChoice(vararg branchNames: String): XMLSequencePattern {
        return XMLSequencePattern(branchNames.map(::xmlElement))
    }

    private fun choiceNames(choiceGroup: XMLSequencePattern): List<String> {
        return choiceGroup.members.map { pattern ->
            ((pattern as XMLPattern).pattern.name).substringAfter(":")
        }
    }

    private fun expandedChoiceNames(choiceGroup: XMLSequencePattern): List<String> {
        return choiceGroup.members.flatMap { pattern ->
            patternNames(pattern)
        }
    }

    private fun patternNames(pattern: Pattern): List<String> {
        return when (pattern) {
            is XMLPattern -> listOf(pattern.pattern.name.substringAfter(":"))
            is XMLChoiceGroupPattern -> pattern.choices.flatten().flatMap(::patternNames)
            is XMLSequencePattern -> pattern.members.flatMap(::patternNames)
            else -> emptyList()
        }
    }

    private fun generatedNodeNames(choiceGroup: XMLSequencePattern): List<String> {
        val generated = choiceGroup.generate(resolver) as io.specmatic.core.value.XMLNode

        return generatedNodeNames(generated)
    }

    private fun generatedNodeNames(generated: XMLNode): List<String> {
        return generated.childNodes.filterIsInstance<io.specmatic.core.value.XMLNode>().map { childNode ->
            childNode.name.substringAfter(":")
        }
    }

    private fun xmlElement(name: String): XMLPattern = XMLPattern("<$name>(string)</$name>")

    private fun assertSuccess(result: Result) {
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    private fun assertFailure(result: Result) {
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }
}

private class FixedXMLChoiceGenerationDecisions(
    private val includeOptional: Boolean = true,
    private val branchIndex: Int = 0
) : XMLGenerationDecisions {
    override fun includeOptionalXMLNode(): Boolean = includeOptional

    override fun chooseXMLChoiceBranch(choiceCount: Int): Int = branchIndex.coerceIn(0, choiceCount - 1)
}
