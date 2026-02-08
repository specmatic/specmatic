package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.v3.components.sources.SourceV3
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
}
