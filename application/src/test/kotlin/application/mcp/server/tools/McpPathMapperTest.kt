package application.mcp.server.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class McpPathMapperTest {
    @Test
    fun `translate should map matching host prefix to container prefix`() {
        val mapper = McpPathMapper()

        assertThat(
            mapper.translate(
                "C:\\specmaticProjects\\sampleProjects\\specmatic-order-contracts",
            )
        )
            .isEqualTo("/usr/src/app")
    }

    @Test
    fun `translate should leave non matching paths unchanged`() {
        val mapper = McpPathMapper()

        assertThat(mapper.translate("D:\\other\\repo"))
            .isEqualTo("/usr/src/app")
        assertThat(mapper.translate("/usr/src/app/specmatic-order-contracts"))
            .isEqualTo("/usr/src/app/specmatic-order-contracts")
    }
}
