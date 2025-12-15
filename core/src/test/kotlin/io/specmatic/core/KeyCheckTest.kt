package io.specmatic.core

import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.StringValue
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class KeyCheckTest {
    @Test
    fun `should invoke the pattern key check`() {
        val result = DefaultKeyCheckImpl(patternKeyCheck = object: KeyErrorCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): MissingKeyError {
                return MissingKeyError("test")
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError> {
                return listOf(MissingKeyError("test"))
            }

            override fun validateListCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError> {
                TODO("Not yet implemented")
            }

        }).validate(emptyMap(), emptyMap())

        assertThat(result?.name).isEqualTo("test")
    }

    @Test
    fun `should invoke the unexpected key check if the pattern key check returns nothing`() {
        val result = DefaultKeyCheckImpl(patternKeyCheck = object: KeyErrorCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): MissingKeyError? {
                return null
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError> {
                return emptyList()
            }

            override fun validateListCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError> {
                TODO("Not yet implemented")
            }

        }, unexpectedKeyCheck = object: UnexpectedKeyCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): UnexpectedKeyError {
                return UnexpectedKeyError("test")
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
                return listOf(UnexpectedKeyError("test"))
            }

            override fun validateListCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
                TODO("Not yet implemented")
            }

        }).validate(emptyMap(), emptyMap())

        assertThat(result?.name).isEqualTo("test")
    }

    @Test
    fun `override the unexpected key check`() {
        val checker = DefaultKeyCheckImpl(patternKeyCheck = object: KeyErrorCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): MissingKeyError? {
                return null
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError> {
                return emptyList()
            }

            override fun validateListCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError> {
                TODO("Not yet implemented")
            }

        }, unexpectedKeyCheck = object: UnexpectedKeyCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): UnexpectedKeyError {
                return UnexpectedKeyError("test")
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
                return listOf(UnexpectedKeyError("test"))
            }

            override fun validateListCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
                TODO("Not yet implemented")
            }

        }).withUnexpectedKeyCheck(IgnoreUnexpectedKeys)

        val result = checker.validate(emptyMap(), emptyMap())

        assertThat(result?.name).isNull()
    }

    @Test
    fun `prevent overriding of the unexpected key check`() {
        val checker = DefaultKeyCheckImpl(patternKeyCheck = object: KeyErrorCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): MissingKeyError? {
                return null
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError> {
                return emptyList()
            }

            override fun validateListCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError> {
                TODO("Not yet implemented")
            }

        }, unexpectedKeyCheck = object: UnexpectedKeyCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): UnexpectedKeyError {
                return UnexpectedKeyError("test")
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
                return listOf(UnexpectedKeyError("test"))
            }

            override fun validateListCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
                TODO("Not yet implemented")
            }

        }).disableOverrideUnexpectedKeyCheck().withUnexpectedKeyCheck(IgnoreUnexpectedKeys)

        val result = checker.validate(emptyMap(), emptyMap())

        assertThat(result?.name).isEqualTo("test")
    }

    @Test
    fun `should invoke case insensitive matchers`() {
        val keyErrorCheck = mockk<KeyErrorCheck>()

        every {
            keyErrorCheck.validateListCaseInsensitive(any(), any())
        }.returns(listOf(MissingKeyError("key1")))

        val unexpectedKeyCheck = mockk<UnexpectedKeyCheck>()

        every {
            unexpectedKeyCheck.validateListCaseInsensitive(any(), any())
        }.returns(listOf(UnexpectedKeyError("key2")))

        val errors = DefaultKeyCheckImpl(keyErrorCheck, unexpectedKeyCheck).validateAllCaseInsensitive(emptyMap(), emptyMap())

        assertThat(errors[0].name).isEqualTo("key1")
        assertThat(errors[1].name).isEqualTo("key2")
    }

    @Test
    fun `isPartial should return true when keyCheck is partial key check`() {
        val partialKeyChecks = listOf(
            DefaultKeyCheckImpl(patternKeyCheck = noPatternKeyCheck, unexpectedKeyCheck = IgnoreUnexpectedKeys),
            DefaultKeyCheckImpl(patternKeyCheck = noPatternKeyCheck, unexpectedKeyCheck = ValidateUnexpectedKeys),
        )

        assertThat(partialKeyChecks).allSatisfy { assertThat(it.isPartial).isTrue() }
    }
}
