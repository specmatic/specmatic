package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import io.specmatic.shouldMatch
import io.specmatic.shouldNotMatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

internal class UUIDPatternTest {
    private val pattern = UUIDPattern()

    @Test
    fun `should parse a valid UUIDvalue`() {
        val uuidString = UUID.randomUUID().toString()
        val uuidValue = pattern.parse(uuidString, Resolver())

        assertEquals(uuidString, uuidValue.string)
    }

    @Test
    fun `should generate a UUIDvalue which can be parsed`() {
        val valueGenerated = pattern.generate(Resolver())
        val valueParsed = pattern.parse(valueGenerated.string, Resolver())

        assertEquals(valueGenerated, valueParsed)
    }

    @Test
    fun `should match a valid UUIDvalue`() {
        val valueGenerated = pattern.generate(Resolver())
        valueGenerated shouldMatch pattern
    }

    @Test
    fun `should fail to match an invalid UUIDvalue`() {
        val valueGenerated = StringValue("this is not a UUID value")
        valueGenerated shouldNotMatch pattern
    }

    @Test
    fun `should return itself when generating a new pattern based on a row`() {
        val uuidPatterns = pattern.newBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertEquals(1, uuidPatterns.size)
        assertEquals(pattern, uuidPatterns.first())
    }

    @Test
    fun `should match this UUIDtime format`() {
        val uuid1 = StringValue("cfb64879-6e10-47f6-a824-a7606a36d423")
        val uuid2 = StringValue("3b45392f-3e4d-440e-b680-2ab673a197a6")

        assertThat(pattern.matches(uuid1, Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.matches(uuid2, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    @Tag(GENERATION)
    fun `negative values should be generated`() {
        val result = pattern.negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder("string", "number", "boolean", "null")
    }

    @Test
    fun `should use the provided uuid example during generation`() {
        val example = "123e4567-e89b-12d3-a456-426655440000"
        val patternWithExample = UUIDPattern(example = example)

        val generated = patternWithExample.generate(Resolver())

        assertEquals(example, generated.string)
    }
}
