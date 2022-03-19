package `in`.specmatic.core

import `in`.specmatic.core.pattern.IgnoreUnexpectedKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class KeyCheckTest {
    @Test
    fun `should invoke the pattern key check`() {
        val result = KeyCheck(patternKeyCheck = object: KeyErrorCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): KeyError {
                return MissingKeyError("test")
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<KeyError> {
                return listOf(MissingKeyError("test"))
            }

        }).validate(emptyMap(), emptyMap())

        assertThat(result?.name).isEqualTo("test")
    }

    @Test
    fun `should invoke the unexpected key check if the pattern key check returns nothing`() {
        val result = KeyCheck(patternKeyCheck = object: KeyErrorCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): KeyError? {
                return null
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<KeyError> {
                return emptyList()
            }

        }, unexpectedKeyCheck = object: UnexpectedKeyCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): UnexpectedKeyError {
                return UnexpectedKeyError("test")
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
                return listOf(UnexpectedKeyError("test"))
            }

        }).validate(emptyMap(), emptyMap())

        assertThat(result?.name).isEqualTo("test")
    }

    @Test
    fun `override the unexpected key check`() {
        val checker = KeyCheck(patternKeyCheck = object: KeyErrorCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): KeyError? {
                return null
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<KeyError> {
                return emptyList()
            }

        }, unexpectedKeyCheck = object: UnexpectedKeyCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): UnexpectedKeyError {
                return UnexpectedKeyError("test")
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
                return listOf(UnexpectedKeyError("test"))
            }

        }).withUnexpectedKeyCheck(IgnoreUnexpectedKeys)

        val result = checker.validate(emptyMap(), emptyMap())

        assertThat(result?.name).isNull()
    }

    @Test
    fun `prevent overriding of the unexpected key check`() {
        val checker = KeyCheck(patternKeyCheck = object: KeyErrorCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): KeyError? {
                return null
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<KeyError> {
                return emptyList()
            }

        }, unexpectedKeyCheck = object: UnexpectedKeyCheck {
            override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): UnexpectedKeyError {
                return UnexpectedKeyError("test")
            }

            override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
                return listOf(UnexpectedKeyError("test"))
            }

        }).disableOverrideUnexpectedKeycheck().withUnexpectedKeyCheck(IgnoreUnexpectedKeys)

        val result = checker.validate(emptyMap(), emptyMap())

        assertThat(result?.name).isEqualTo("test")
    }
}
