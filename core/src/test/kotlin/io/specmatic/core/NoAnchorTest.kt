package io.specmatic.core

import io.specmatic.osAgnosticPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class NoAnchorTest {
    @Test
    fun `should return the path given`() {
        val path = "/some/path"
        assertThat(osAgnosticPath(NoAnchor.resolve(path).path)).isEqualTo(osAgnosticPath(path))
    }
}
