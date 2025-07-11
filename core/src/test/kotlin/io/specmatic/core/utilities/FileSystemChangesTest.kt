package io.specmatic.core.utilities

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.CONTRACT_EXTENSIONS
import io.specmatic.core.EXAMPLES_DIR_SUFFIX
import io.specmatic.core.JSON
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.WatchKey

class FileSystemChangesTest {
    private lateinit var key: WatchKey

    @BeforeEach
    fun setUp() {
        key = mockk()
    }

    private fun eventMock(filename: String): WatchEvent<Any> {
        val event = mockk<WatchEvent<Any>>()
        every { event.context() } returns filename
        return event
    }

    @Test
    fun `hasNoEvents is true when there are no events`() {
        every { key.pollEvents() } returns emptyList()
        val changes = FileSystemChanges(key)
        assertTrue(changes.hasNoEvents)
        assertEquals("", changes.filesWithEvents)
        assertTrue(changes.interestingEvents.isEmpty())
    }

    @Test
    fun `filesWithEvents joins event names`() {
        every { key.pollEvents() } returns listOf(eventMock("foo.txt"), eventMock("bar.json"))
        val changes = FileSystemChanges(key)
        assertEquals("foo.txt, bar.json", changes.filesWithEvents)
    }

    @Test
    fun `interestingEvents filters for contract extension`() {
        val contractFile = "api.${CONTRACT_EXTENSIONS.first()}"
        every { key.pollEvents() } returns listOf(eventMock(contractFile), eventMock("readme.md"))
        every { key.watchable() } returns null
        val changes = FileSystemChanges(key)
        assertEquals(listOf(contractFile), changes.interestingEvents)
    }

    @Test
    fun `interestingEvents filters for json file`() {
        val jsonFile = "data.$JSON"
        every { key.pollEvents() } returns listOf(eventMock(jsonFile), eventMock("notes.txt"))
        every { key.watchable() } returns null
        val changes = FileSystemChanges(key)
        assertEquals(listOf(jsonFile), changes.interestingEvents)
    }

    @Test
    fun `interestingEvents filters for file name ending with examples dir suffix`() {
        val examplesFile = "contract$EXAMPLES_DIR_SUFFIX"
        every { key.pollEvents() } returns listOf(eventMock(examplesFile), eventMock("other.txt"))
        every { key.watchable() } returns null
        val changes = FileSystemChanges(key)
        assertEquals(listOf(examplesFile), changes.interestingEvents)
    }

    @Test
    fun `interestingEvents filters for parent directory ending with examples dir suffix`() {
        val file = "foo.txt"
        val path = mockk<Path>()
        every { path.toString() } returns "some/path$EXAMPLES_DIR_SUFFIX"
        every { key.pollEvents() } returns listOf(eventMock(file))
        every { key.watchable() } returns path
        val changes = FileSystemChanges(key)
        assertEquals(listOf(file), changes.interestingEvents)
    }

    @Test
    fun `interestingEvents filters for parent directory containing examples dir suffix with separator`() {
        val file = "foo.txt"
        val path = mockk<Path>()
        every { path.toString() } returns "some${File.separator}spec_examples${File.separator}dir"
        every { key.pollEvents() } returns listOf(eventMock(file))
        every { key.watchable() } returns path
        val changes = FileSystemChanges(key)
        assertEquals(listOf(file), changes.interestingEvents)
    }

    @Test
    fun `ignores blank filenames`() {
        every { key.pollEvents() } returns listOf(eventMock(""), eventMock("   "))
        val changes = FileSystemChanges(key)
        assertTrue(changes.hasNoEvents)
        assertEquals("", changes.filesWithEvents)
        assertTrue(changes.interestingEvents.isEmpty())
    }

    @Test
    fun `interesting handles exception gracefully`() {
        val badEvent = mockk<WatchEvent<Any>>()
        every { badEvent.context() } returns null
        every { key.pollEvents() } returns listOf(badEvent)
        val changes = FileSystemChanges(key)
        assertTrue(changes.interestingEvents.isEmpty())
    }
}
