package io.specmatic.core.filters

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContainsExtensionsTest {

    @Test
    fun `caseInsensitiveContains returns true when exact string is present`() {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseInsensitiveContains("banana")).isTrue()
    }

    @Test
    fun `caseInsensitiveContains returns true when string is present with different casing`() {
        val items = listOf("apple", "BANANA", "cherry")
        assertThat(items.caseInsensitiveContains("banana")).isTrue()
        assertThat(items.caseInsensitiveContains("APPLE")).isTrue()
        assertThat(items.caseInsensitiveContains("Cherry")).isTrue()
    }

    @Test
    fun `caseInsensitiveContains returns false when string is not present`() {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseInsensitiveContains("orange")).isFalse()
        assertThat(items.caseInsensitiveContains("grape")).isFalse()
    }

    @Test
    fun `caseInsensitiveContains handles trailing question mark in haystack items`() {
        val items = listOf("apple?", "banana", "cherry?")
        assertThat(items.caseInsensitiveContains("apple")).isTrue()
        assertThat(items.caseInsensitiveContains("cherry")).isTrue()
        assertThat(items.caseInsensitiveContains("banana")).isTrue()
    }

    @Test
    fun `caseInsensitiveContains works with empty lists`() {
        val emptyItems = emptyList<String>()
        assertThat(emptyItems.caseInsensitiveContains("anything")).isFalse()
    }

    @Test
    fun `caseInsensitiveContains handles whitespace and trimming`() {
        val items = listOf("  apple  ", "\tbanana\t", " cherry ")
        assertThat(items.caseInsensitiveContains("apple")).isTrue()
        assertThat(items.caseInsensitiveContains("BANANA")).isTrue()
        assertThat(items.caseInsensitiveContains("Cherry")).isTrue()
    }

    @Test
    fun `caseInsensitiveContains handles whitespace with question marks`() {
        val items = listOf("  apple?  ", "\tbanana?\t", " cherry? ")
        assertThat(items.caseInsensitiveContains("apple")).isTrue()
        assertThat(items.caseInsensitiveContains("BANANA")).isTrue()
        assertThat(items.caseInsensitiveContains("Cherry")).isTrue()
    }

    @Test
    fun `caseInsensitiveContains does not match needle with question mark when haystack has no question mark`() {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseInsensitiveContains("apple?")).isFalse()
        assertThat(items.caseInsensitiveContains("banana?")).isFalse()
    }

    @Test
    fun `caseSensitiveContains returns true when exact string is present`() {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseSensitiveContains("banana")).isTrue()
        assertThat(items.caseSensitiveContains("apple")).isTrue()
    }

    @Test
    fun `caseSensitiveContains returns false when string is present with different casing`() {
        val items = listOf("apple", "BANANA", "cherry")
        assertThat(items.caseSensitiveContains("APPLE")).isFalse()
        assertThat(items.caseSensitiveContains("banana")).isFalse()
        assertThat(items.caseSensitiveContains("Cherry")).isFalse()
        // But should match exact case
        assertThat(items.caseSensitiveContains("apple")).isTrue()
        assertThat(items.caseSensitiveContains("BANANA")).isTrue()
        assertThat(items.caseSensitiveContains("cherry")).isTrue()
    }

    @Test
    fun `caseSensitiveContains returns false when string is not present`() {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseSensitiveContains("orange")).isFalse()
        assertThat(items.caseSensitiveContains("grape")).isFalse()
    }

    @Test
    fun `caseSensitiveContains handles trailing question mark in haystack items`() {
        val items = listOf("apple?", "banana", "cherry?")
        assertThat(items.caseSensitiveContains("apple")).isTrue()
        assertThat(items.caseSensitiveContains("cherry")).isTrue()
        assertThat(items.caseSensitiveContains("banana")).isTrue()
    }

    @Test
    fun `caseSensitiveContains works with empty lists`() {
        val emptyItems = emptyList<String>()
        assertThat(emptyItems.caseSensitiveContains("anything")).isFalse()
    }

    @Test
    fun `caseSensitiveContains handles whitespace and trimming`() {
        val items = listOf("  apple  ", "\tbanana\t", " cherry ")
        assertThat(items.caseSensitiveContains("apple")).isTrue()
        assertThat(items.caseSensitiveContains("banana")).isTrue()
        assertThat(items.caseSensitiveContains("cherry")).isTrue()
        // Should not match different case even after trimming
        assertThat(items.caseSensitiveContains("APPLE")).isFalse()
    }

    @Test
    fun `caseSensitiveContains handles whitespace with question marks`() {
        val items = listOf("  apple?  ", "\tbanana?\t", " cherry? ")
        assertThat(items.caseSensitiveContains("apple")).isTrue()
        assertThat(items.caseSensitiveContains("banana")).isTrue()
        assertThat(items.caseSensitiveContains("cherry")).isTrue()
        // Should not match different case even after trimming and removing question mark
        assertThat(items.caseSensitiveContains("APPLE")).isFalse()
    }

    @Test
    fun `caseSensitiveContains does not match needle with question mark when haystack has no question mark`() {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseSensitiveContains("apple?")).isFalse()
        assertThat(items.caseSensitiveContains("banana?")).isFalse()
    }

    @Test
    fun `both functions handle multiple question marks correctly`() {
        val items = listOf("apple??", "banana?", "cherry")
        // removeSuffix only removes one trailing "?" 
        assertThat(items.caseInsensitiveContains("apple?")).isTrue()
        assertThat(items.caseSensitiveContains("apple?")).isTrue()
        assertThat(items.caseInsensitiveContains("banana")).isTrue()
        assertThat(items.caseSensitiveContains("banana")).isTrue()
    }

    @Test
    fun `both functions handle empty strings in collections`() {
        val items = listOf("", "apple", "banana")
        assertThat(items.caseInsensitiveContains("")).isTrue()
        assertThat(items.caseSensitiveContains("")).isTrue()
    }

    @Test
    fun `both functions handle collections with only empty and whitespace strings`() {
        val items = listOf("", "   ", "\t\t")
        assertThat(items.caseInsensitiveContains("")).isTrue()
        assertThat(items.caseSensitiveContains("")).isTrue()
    }
}