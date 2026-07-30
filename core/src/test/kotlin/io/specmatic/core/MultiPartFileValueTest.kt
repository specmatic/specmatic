package io.specmatic.core

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.value.StringValue

internal class MultiPartFileValueTest {
    @Test
    fun `should generate a matching pattern`() {
        val pattern = MultiPartFileValue("some name", "@customers.csv", "text/csv", "gzip").inferType()
        Assertions.assertThat(pattern.name).isEqualTo("some name")
        Assertions.assertThat(pattern.filename).isEqualTo(
            FilenamePattern.Match(ExactValuePattern(StringValue("customers.csv")))
        )
        Assertions.assertThat(pattern.contentType).isEqualTo("text/csv")
        Assertions.assertThat(pattern.contentEncoding).isEqualTo("gzip")
    }
}
