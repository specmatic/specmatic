package io.specmatic.core

import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.keySets
import org.junit.jupiter.api.Assertions.assertEquals

class KeySetsTest {
    @Test
    fun `Given a single optional key, two sets of keys should be generated, one with and one without the key` () {
        val listOfKeys = listOf("key1", "key2?")
        val expectedKeySets = listOf(listOf("key1"), listOf("key1", "key2?"))

        assertEquals(expectedKeySets, keySets(listOfKeys, Row()).toList())
    }
}
