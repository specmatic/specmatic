package io.specmatic.stub

import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SSEBufferTest {
    @Test
    fun `should allow an event to be buffered while buffered events are being replayed`() {
        val replayStarted = CountDownLatch(1)
        val newEventBuffered = CountDownLatch(1)
        val buffer = SSEBuffer().apply {
            add(SseEvent(data = "first", bufferIndex = -1))
            add(SseEvent(data = "second", bufferIndex = -1))
        }
        val writer = writerThatPausesReplay(replayStarted, newEventBuffered)

        val bufferNewEvent = thread {
            check(replayStarted.await(5, TimeUnit.SECONDS))
            buffer.add(SseEvent(data = "third", bufferIndex = -1))
            newEventBuffered.countDown()
        }

        assertThatCode { buffer.write(writer) }.doesNotThrowAnyException()
        bufferNewEvent.join()
    }

    private fun writerThatPausesReplay(
        replayStarted: CountDownLatch,
        newEventBuffered: CountDownLatch,
    ): Writer {
        val output = StringWriter()
        var firstWrite = true

        return object : Writer() {
            override fun write(characters: CharArray, offset: Int, length: Int) {
                output.write(characters, offset, length)
                if (firstWrite) {
                    firstWrite = false
                    replayStarted.countDown()
                    check(newEventBuffered.await(5, TimeUnit.SECONDS))
                }
            }

            override fun flush() = output.flush()
            override fun close() = output.close()
        }
    }
}
