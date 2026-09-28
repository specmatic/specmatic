package io.specmatic.core.pattern

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class RFC3339Test {
    @Test
    fun `accepts UTC and numeric offsets required by RFC 3339`() {
        listOf(
            "2026-09-23T07:00:26Z",
            "2026-09-23T07:00:26z",
            "2026-09-23T07:00:26+05:30",
            "2026-09-23T07:00:26-05:30",
            "2026-09-23T07:00:26-00:00"
        ).forEach { assertDoesNotThrow { RFC3339.parse(it) } }
    }

    @Test
    fun `accepts lowercase t and arbitrary fractional-second precision allowed by RFC 3339`() {
        assertDoesNotThrow { RFC3339.parse("2026-09-23t07:00:26.123456789123Z") }
    }

    @Test
    fun `accepts second 60 because RFC 3339 represents leap seconds`() {
        assertDoesNotThrow { RFC3339.parse("1990-12-31T23:59:60Z") }
    }

    @Test
    fun `accepts valid upper time and offset bounds`() {
        assertDoesNotThrow { RFC3339.parse("2026-09-23T23:59:59+23:59") }
    }

    @Test
    fun `accepts February 29 in a leap year`() {
        assertDoesNotThrow { RFC3339.parse("2024-02-29T07:00:26Z") }
    }

    @Test
    fun `rejects a plain date because RFC 3339 date-time requires a time and offset`() {
        assertInvalid("2026-09-23")
    }

    @Test
    fun `rejects a local date-time because RFC 3339 requires an offset`() {
        assertInvalid("2026-09-23T07:00:26")
    }

    @Test
    fun `rejects malformed date-time syntax before semantic validation`() {
        listOf(
            "2026-09-23 07:00:26Z",
            "2026-09-23T07:00:26:",
            "2026-09-23T07:00:26.Z"
        ).forEach(::assertInvalid)
    }

    @Test
    fun `rejects impossible calendar dates because matching digits alone is insufficient`() {
        listOf(
            "2026-13-23T07:00:26Z",
            "2026-02-29T07:00:26Z"
        ).forEach(::assertInvalid)
    }

    @Test
    fun `rejects hour 24 because RFC 3339 hours range from 00 through 23`() {
        assertInvalid("2026-09-23T24:00:00Z")
    }

    @Test
    fun `rejects minute 60 because RFC 3339 minutes range from 00 through 59`() {
        assertInvalid("2026-09-23T23:60:00Z")
    }

    @Test
    fun `rejects second 61 while retaining RFC 3339 leap second 60`() {
        assertInvalid("2026-09-23T23:59:61Z")
    }

    @Test
    fun `rejects offset hour 24 because RFC 3339 offset hours range from 00 through 23`() {
        assertInvalid("2026-09-23T07:00:26+24:00")
    }

    @Test
    fun `rejects offset minute 60 because RFC 3339 offset minutes range from 00 through 59`() {
        assertInvalid("2026-09-23T07:00:26+05:60")
    }

    private fun assertInvalid(dateTime: String) {
        assertThrows<ContractException> { RFC3339.parse(dateTime) }
    }
}
