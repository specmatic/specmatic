package io.specmatic.core.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LogOutputConfigTest {

    @Nested
    inner class DefaultOrTest {
        private val defaultConfig = LogOutputConfig(directory = null, console = true)
        @Test
        fun `should return defaultConfig if logOutputConfig is null` () {
            val result = LogOutputConfig.defaultOr(null)

            assertThat(result).isEqualTo(defaultConfig)
        }

        @Test
        fun `should return defaultConfig if directory and console config from the passed logOutputConfig is null` () {
            val result = LogOutputConfig.defaultOr(LogOutputConfig(directory = null, console = null))

            assertThat(result).isEqualTo(defaultConfig)
        }

        @Test
        fun `should return passed logOutputConfig if directory is null but console config is not null` () {
            val logOutputConfig = LogOutputConfig(directory = null, console = true)

            val result = LogOutputConfig.defaultOr(logOutputConfig)

            assertThat(result).isEqualTo(logOutputConfig)
        }

        @Test
        fun `should return passed logOutputConfig if directory is not null but console config is null` () {
            val logOutputConfig = LogOutputConfig(directory = "", console = null)

            val result = LogOutputConfig.defaultOr(logOutputConfig)

            assertThat(result).isEqualTo(logOutputConfig)
        }

        @Test
        fun `should return passed logOutputConfig if directory and console config are not null` () {
            val logOutputConfig = LogOutputConfig(directory = "", console = true)

            val result = LogOutputConfig.defaultOr(logOutputConfig)

            assertThat(result).isEqualTo(logOutputConfig)
        }
    }

}