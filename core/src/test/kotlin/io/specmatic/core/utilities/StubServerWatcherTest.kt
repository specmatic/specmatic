package io.specmatic.core.utilities

import org.assertj.core.api.AssertionsForClassTypes.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class StubServerWatcherTest {
    @Test
    fun `should generate examples directory path with _examples suffix when the dir exists`() {
        val apiSpecFile = File("src/test/resources/openapi/api_specification_with_externalised_stub.yaml")
        val examplesDirectory =
            StubServerWatcher(emptyList()).dataDirOf(apiSpecFile)
        assertThat(examplesDirectory).isEqualTo("${apiSpecFile.absoluteFile.parent}/api_specification_with_externalised_stub_examples")
    }

    @Test
    fun `should generate examples directory path with _data suffix when the _examples dir does not exists`() {
        val apiSpecFile = File("src/test/resources/openapi/api_specification_without_examples_dir.yaml")
        val examplesDirectory =
            StubServerWatcher(emptyList()).dataDirOf(apiSpecFile)
        assertThat(examplesDirectory).isEqualTo("${apiSpecFile.absoluteFile.parent}/api_specification_without_examples_dir_data")
    }
}
