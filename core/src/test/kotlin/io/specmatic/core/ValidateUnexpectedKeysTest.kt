package io.specmatic.core

import io.specmatic.core.pattern.NullPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class ValidateUnexpectedKeysTest {
    @Test
    fun `flags unexpected keys`() {
        val expected = mapOf("hello" to NullPattern)
        val actual = mapOf("world" to NullPattern)

        val error = ValidateUnexpectedKeys.validate(expected, actual)
        assertThat(error?.name).isEqualTo("world")
        assertThat(error).isInstanceOf(UnexpectedKeyError::class.java)
    }

    @Test
    fun `flags unexpected keys and leaves expxected alone`() {
        val expected = mapOf("hello" to NullPattern)
        val actual = mapOf("hello" to NullPattern, "world" to NullPattern)

        val error = ValidateUnexpectedKeys.validate(expected, actual)
        assertThat(error?.name).isEqualTo("world")
        assertThat(error).isInstanceOf(UnexpectedKeyError::class.java)
    }

    @Test
    fun `does not return any error when all the keys are expected`() {
        val expected = mapOf("hello" to NullPattern)
        val actual = mapOf("hello" to NullPattern)

        val error = ValidateUnexpectedKeys.validate(expected, actual)
        assertThat(error).isNull()
    }

    @Nested
    inner class AllUnexpectedErrors {
        private val expected = mapOf("hello" to NullPattern)
        private val actual = mapOf("hello_there" to NullPattern, "hello_world" to NullPattern)
        private val errorList: List<UnexpectedKeyError> = ValidateUnexpectedKeys.validateList(expected, actual)

        @Test
        fun `returns as many errors as unexpected keys`() {
            assertThat(errorList).hasSize(2)
        }

        @Test
        fun `errors should refer to the unexpected keys`() {
            val names = errorList.map { it.name }

            assertThat(names).contains("hello_there")
            assertThat(names).contains("hello_world")
        }
    }

    @Nested
    inner class AllUnexpectedErrorsCaseInsensitive {
        private val expected = mapOf("hello" to StringPattern())
        private val actual = mapOf("HELLO" to StringValue("friend"), "hello_there" to StringValue("friend"))
        private val errorList: List<UnexpectedKeyError> = ValidateUnexpectedKeys.validateListCaseInsensitive(expected, actual)

        @Test
        fun `returns as many errors as unexpected keys`() {
            assertThat(errorList).hasSize(1)
        }

        @Test
        fun `errors should refer to the unexpected keys`() {
            val names = errorList.map { it.name }

            assertThat(names).contains("hello_there")
        }
    }
}
