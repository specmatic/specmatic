package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.v3.ServerOrigin
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.utilities.Flags
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SpecificationDefinitionTest {
    @Test
    fun `matchesFile should match relative spec file paths for filesystem source`(@TempDir tempDir: File) {
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))
        val definition = SpecificationDefinition.StringValue("contracts/orders.yaml")

        val relativeInputPath = File("contracts/orders.yaml")

        assertThat(definition.matchesFile(source, relativeInputPath)).isTrue()
    }

    @Test
    fun `getServerOriginWithBasePath should append urlPathPrefix to server origin`() {
        val definition = SpecificationDefinition.ObjectValue(
            SpecificationDefinition.Specification(
                id = "orders",
                urlPathPrefix = "/v1/",
                path = "contracts/orders.yaml",
            )
        )

        val serverOrigin = ServerOrigin.NetworkAddress(host = "localhost", port = 8080)
        assertThat(definition.getServerOriginWithBasePath(serverOrigin)).isEqualTo(
            ServerOrigin.from("http://localhost:8080/v1")
        )
    }

    @Test
    fun `getServerOriginWithBasePath should use SPECMATIC_BASE_URL when server origin is missing`() {
        val definition = SpecificationDefinition.ObjectValue(
            SpecificationDefinition.Specification(
                id = "orders",
                urlPathPrefix = "v1",
                path = "contracts/orders.yaml",
            )
        )

        Flags.using(Flags.SPECMATIC_BASE_URL to "http://default.example:9090") {
            assertThat(definition.getServerOriginWithBasePath(null)).isEqualTo(
                ServerOrigin.from("http://default.example:9090/v1")
           )
        }
    }
}
