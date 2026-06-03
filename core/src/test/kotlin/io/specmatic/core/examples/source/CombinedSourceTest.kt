package io.specmatic.core.examples.source

import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CombinedSourceTest {
    @Test
    fun `should merge same operation from multiple preloaded sources`(@TempDir tempDir: File) {
        val firstExample = tempDir.resolve("first.json").apply { writeText(exampleFile("first")) }
        val secondExample = tempDir.resolve("second.json").apply { writeText(exampleFile("second")) }
        val combinedSource = CombinedSource(
            sources = listOf(
                PreLoadedExampleObjects(listOf(ScenarioStub.readFromFile(firstExample)), SpecmaticConfigV1V2Common()),
                PreLoadedExampleObjects(listOf(ScenarioStub.readFromFile(secondExample)), SpecmaticConfigV1V2Common())
            )
        )

        val examples = combinedSource.examples
        val rows = examples.values.single()
        assertThat(examples).hasSize(1)
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.fileSource }).containsExactlyInAnyOrder(
            firstExample.canonicalPath,
            secondExample.canonicalPath
        )
    }

    private fun exampleFile(source: String): String = """{
      "name": "$source",
      "http-request": {
        "path": "/test",
        "method": "GET",
        "body": { "source": "$source" }
      },
      "http-response": { "status": 200 }
    }""".trimIndent()
}
