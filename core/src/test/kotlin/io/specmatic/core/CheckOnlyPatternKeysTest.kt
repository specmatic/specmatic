package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class CheckOnlyPatternKeysTest {
    @Test
    fun `checkOnlyPatternKeys should succeed when expected keys are all found`() {
        val expected = mapOf("expected" to "value")
        val actual = mapOf("expected" to "value")
        val missing = CheckOnlyPatternKeys.validate(expected, actual)

        assertThat(missing).isNull()
    }

    @Test
    fun `checkOnlyPatternKeys should catch missing expected keys`() {
        val expected = mapOf("expected" to "value")
        val actual = mapOf("unexpected" to "value")
        val missing = CheckOnlyPatternKeys.validate(expected, actual)

        assertThat(missing).isEqualTo(MissingKeyError("expected"))
    }

    @Test
    fun `checkOnlyPatternKeys should allow unexpected keys given the ellipsis key`() {
        val expected = mapOf("expected" to "value", "..." to "")
        val actual = mapOf("expected" to "value", "unexpected" to "value")
        val missing = CheckOnlyPatternKeys.validate(expected, actual)

        assertThat(missing).isEqualTo(null)
    }

    @Test
    fun `checkOnlyPatternKeys should allow missing optional expected keys`() {
        val expected = mapOf("expected-optional?" to "value")
        val actual = emptyMap<String, String>()
        val missing = CheckOnlyPatternKeys.validate(expected, actual)

        assertThat(missing).isEqualTo(null)
    }

    @Test
    fun `checkOnlyPatternKeys should match actual keys with expected optional keys`() {
        val expected = mapOf("expected-optional?" to "value")
        val actual = mapOf("expected-optional" to "value")
        val missing = CheckOnlyPatternKeys.validate(expected, actual)

        assertThat(missing).isEqualTo(null)
    }

    @Test
    fun `checkOnlyPatternKeys should match actual optional keys with expected optional keys`() {
        val expected = mapOf("expected-optional?" to "value")
        val actual = mapOf("expected-optional?" to "value")
        val missing = CheckOnlyPatternKeys.validate(expected, actual)

        assertThat(missing).isEqualTo(null)
    }

    @Nested
    inner class ReturnAllErrors {
        val expected = mapOf("hello" to "value", "world" to "value")
        val actual = mapOf("hello_world" to "value")
        private val missingList: List<KeyError> = CheckOnlyPatternKeys.validateList(expected, actual)

        @Test
        fun `should return as many errors as the number of missing keys`() {
            assertThat(missingList).hasSize(2)
        }

        @Test
        fun `the errors should refer to the missing keys`() {
            val keys = missingList.map { it.name }

            assertThat(keys).contains("hello")
            assertThat(keys).contains("world")
        }
    }
}
