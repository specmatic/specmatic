package io.specmatic.core

import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FuzzyUnexpectedKeyCheckTest {
    private val mismatchMessages = DefaultMismatchMessages
    private val pattern: Map<String, Pattern> = mapOf("userName" to StringPattern(), "emailAddress" to StringPattern(), "age" to NumberPattern())
    private fun mapOfStrings(vararg pairs: Pair<String, String>): Map<String, StringValue> {
        return pairs.associate { it.first to StringValue(it.second) }
    }

    @Test
    fun `should report fuzzy match as WARNING when delegate is Ignore`() {
        val check = FuzzyUnexpectedKeyCheck(IgnoreUnexpectedKeys)
        val actual = mapOfStrings("usrName" to "john")
        val errors = check.validateList(pattern, actual)

        assertThat(errors).hasSize(1)
        assertThat(errors.single()).isInstanceOf(FuzzyKeyError::class.java)

        val failure = (errors.single() as FuzzyKeyError).missingOptionalKeyToResult("key", mismatchMessages)
        assertThat(failure.message).isEqualToIgnoringWhitespace("Key named \"usrName\" was unexpected, Did you mean 'userName'?")
        assertThat(failure.isPartial).isTrue()
    }

    @Test
    fun `should report fuzzy match as ERROR when delegate is Validate`() {
        val check = FuzzyUnexpectedKeyCheck(ValidateUnexpectedKeys)
        val actual = mapOfStrings("usrName" to "john")
        val errors = check.validateList(pattern, actual)

        assertThat(errors).hasSize(1)
        assertThat(errors.single()).isInstanceOf(FuzzyKeyError::class.java)

        val failure = (errors.single() as FuzzyKeyError).missingOptionalKeyToResult("key", mismatchMessages)
        assertThat(failure.message).isEqualToIgnoringWhitespace("Key named \"usrName\" was unexpected, Did you mean 'userName'?")
        assertThat(failure.isPartial).isFalse()
    }

    @Test
    fun `should ignore random unknown keys when delegate is Ignore`() {
        val check = FuzzyUnexpectedKeyCheck(IgnoreUnexpectedKeys)
        val actual = mapOfStrings("randomField123" to "value")
        val errors = check.validateList(pattern, actual)
        assertThat(errors).isEmpty()
    }

    @Test
    fun `should report standard error for random unknown keys when delegate is Validate`() {
        val check = FuzzyUnexpectedKeyCheck(ValidateUnexpectedKeys)
        val actual = mapOfStrings("randomField123" to "value")
        val errors = check.validateList(pattern, actual)

        assertThat(errors).hasSize(1)
        assertThat(errors.single()).isNotInstanceOf(FuzzyKeyError::class.java)
        assertThat(errors.single()).isInstanceOf(UnexpectedKeyError::class.java)
        assertThat(errors.single().name).isEqualTo("randomField123")
    }

    @Test
    fun `should treat case mismatch as ERROR in STRICT mode`() {
        val check = FuzzyUnexpectedKeyCheck(ValidateUnexpectedKeys)
        val actual = mapOfStrings("username" to "john")
        val errors = check.validateList(pattern, actual)

        assertThat(errors).hasSize(1)
        assertThat(errors.single()).isInstanceOf(FuzzyKeyError::class.java)

        val failure = (errors.single() as FuzzyKeyError).missingKeyToResult("key", mismatchMessages)
        assertThat(failure.message).isEqualToIgnoringWhitespace("Key named \"username\" was unexpected, Did you mean 'userName'?")
        assertThat(failure.isPartial).isFalse()
    }

    @Test
    fun `should treat case mismatch as VALID in CASE-INSENSITIVE mode`() {
        val check = FuzzyUnexpectedKeyCheck(ValidateUnexpectedKeys)
        val actual = mapOfStrings("username" to "john")
        val errors = check.validateListCaseInsensitive(pattern, actual)
        assertThat(errors).isEmpty()
    }

    @Test
    fun `should still catch typos in CASE-INSENSITIVE mode`() {
        val check = FuzzyUnexpectedKeyCheck(ValidateUnexpectedKeys)
        val actual = mapOfStrings("usrname" to "john")
        val errors = check.validateListCaseInsensitive(pattern, actual)

        assertThat(errors).hasSize(1)
        assertThat(errors.single()).isInstanceOf(FuzzyKeyError::class.java)

        val failure = (errors.single() as FuzzyKeyError).missingKeyToResult("key", mismatchMessages)
        assertThat(failure.message).isEqualToIgnoringWhitespace("Key named \"usrname\" was unexpected, Did you mean 'userName'?")
        assertThat(failure.isPartial).isFalse()
    }

    @Test
    fun `should NOT report fuzzy error if correct key is ALSO present`() {
        val check = FuzzyUnexpectedKeyCheck(ValidateUnexpectedKeys)
        val actual = mapOfStrings("userName" to "validValue", "usrName" to "typoValue")
        val errors = check.validateList(pattern, actual)

        assertThat(errors).hasSize(1)
        assertThat(errors.single()).isNotInstanceOf(FuzzyKeyError::class.java)
        assertThat(errors.single()).isInstanceOf(UnexpectedKeyError::class.java)
        assertThat(errors.single().name).isEqualTo("usrName")
    }

    @Test
    fun `should IGNORE fuzzy candidate if correct key is present and delegate is Ignore`() {
        val check = FuzzyUnexpectedKeyCheck(IgnoreUnexpectedKeys)
        val actual = mapOfStrings("userName" to "validValue", "usrName" to "typoValue")
        val errors = check.validateList(pattern, actual)
        assertThat(errors).isEmpty()
    }

    @Test
    fun `should return empty list for valid inputs`() {
        val check = FuzzyUnexpectedKeyCheck(ValidateUnexpectedKeys)
        val actual = mapOfStrings("userName" to "john", "emailAddress" to "john@example.com")
        val errors = check.validateList(pattern, actual)
        assertThat(errors).isEmpty()
    }
}
