package io.specmatic.core.examples.source

import io.specmatic.core.SpecmaticConfigV1V2Common
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DirectoryExampleSourceTest {
    @Test
    fun `should load examples from multiple directories without overwriting same operation`(@TempDir tempDir: File) {
        val firstDir = tempDir.resolve("first_examples").apply { mkdirs() }
        firstDir.resolve("first.json").writeText(exampleFile("first"))

        val secondDir = tempDir.resolve("second_examples").apply { mkdirs() }
        secondDir.resolve("second.json").writeText(exampleFile("second"))

        val exampleSource = DirectoryExampleSource(
            strictMode = true,
            exampleDirs = listOf(firstDir.canonicalPath, secondDir.canonicalPath),
            specmaticConfig = SpecmaticConfigV1V2Common()
        )

        val examples = exampleSource.examples
        val operationIdentifier = examples.keys.single()
        val rows = examples[operationIdentifier].orEmpty()
        assertThat(examples).hasSize(1)
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.fileSource }).containsExactlyInAnyOrder(
            firstDir.resolve("first.json").canonicalPath,
            secondDir.resolve("second.json").canonicalPath
        )
    }

    @Test
    fun `should load nested examples multi levels deep`(@TempDir tempDir: File) {
        val rootDir = tempDir.resolve("root_examples").apply { mkdirs() }
        val levelOne = rootDir.resolve("level_one").apply { mkdirs() }
        val levelTwo = levelOne.resolve("level_two").apply { mkdirs() }
        val levelThree = levelTwo.resolve("level_three").apply { mkdirs() }
        val deepExample = levelThree.resolve("deep.json")

        deepExample.writeText(exampleFile("deep"))
        val exampleSource = DirectoryExampleSource(
            strictMode = true,
            exampleDirs = listOf(rootDir.canonicalPath),
            specmaticConfig = SpecmaticConfigV1V2Common()
        )

        val rows = exampleSource.examples.values.single()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().fileSource).isEqualTo(deepExample.canonicalPath)
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
