package io.specmatic.core.overlay

import org.assertj.core.api.AssertionsForClassTypes.assertThat
import org.junit.jupiter.api.Test

class OverlayParserTest {

    @Test
    fun `should parse overlay content`() {
        val overlayContent = """
            actions:
              - target: ${'$'}.node1
                update: new content for node1
              - target: ${'$'}.node2.subnode
                update: new additional content for subnode
              - target: ${'$'}.node3
                update:
                  newField: updated value
        """.trimIndent()

        val (updateMap, _) = OverlayParser.parse(overlayContent)

        assertThat(updateMap["${'$'}.node1"]?.first()).isEqualTo("new content for node1")
        assertThat(updateMap["${'$'}.node2.subnode"]?.first()).isEqualTo("new additional content for subnode")
        assertThat(updateMap["${'$'}.node3"]?.first()).isEqualTo(
            mapOf(
                "newField" to "updated value"
            )
        )
    }
}
