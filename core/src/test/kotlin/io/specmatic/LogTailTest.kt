package io.specmatic

import io.specmatic.core.log.LogTail
import io.specmatic.core.log.StringLog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class LogTailTest {
    @Test
    fun `should log the given lines`(){
        initializeLog()
        addLogs("one", "two")
        logShouldContain("one", "two")
    }

    @Test
    fun `should rotate logs`() {
        initializeLog()
        addLogs("one", "two", "three")
        logShouldContain("two", "three")
    }

    @Test
    fun `should store snapshot`() {
        initializeLog()
        addLogs("one", "two")
        LogTail.storeSnapshot()
        addLogs("three")
        assertThat(LogTail.getSnapshot()).isEqualTo("one\ntwo")
        logShouldContain("two", "three")
    }

    private fun addLogs(vararg lines: String) {
        for(line in lines) LogTail.append(StringLog(line))
    }

    private fun logShouldContain(vararg lines: String) {
        assertThat(LogTail.getString()).isEqualTo(lines.joinToString("\n"))
    }

    private fun initializeLog() {
        LogTail.clear()
        LogTail.n = 2
    }
}
