package io.specmatic.core.config.v3.components.sources

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SourceV3Test {
    @Test
    fun `withResolvedFilesystemDirectories should resolve relative filesystem directory against working directory`(@TempDir tempDir: File) {
        val workingDir = tempDir.resolve("working").apply { mkdirs() }.canonicalFile
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = "./specs"))

        val resolved = source.withCanonicalizedSources(workingDir)

        assertThat(resolved.getFileSystem()?.directory).isEqualTo(workingDir.resolve("specs").canonicalPath)
        assertThat(source.getFileSystem()?.directory).isEqualTo("./specs")
    }

    @Test
    fun `withResolvedFilesystemDirectories should keep source unchanged when filesystem source is absent`(@TempDir tempDir: File) {
        val source = SourceV3.create(git = SourceV3.Git(url = "https://example.com/contracts.git"))

        val resolved = source.withCanonicalizedSources(tempDir)

        assertThat(resolved).isEqualTo(source)
    }

    @Test
    fun `withResolvedFilesystemDirectories should resolve default filesystem directory against working directory`(@TempDir tempDir: File) {
        val workingDir = tempDir.resolve("working").apply { mkdirs() }.canonicalFile
        val source = SourceV3.create(filesystem = SourceV3.FileSystem())

        val resolved = source.withCanonicalizedSources(workingDir)

        assertThat(resolved.getFileSystem()?.directory).isEqualTo(workingDir.canonicalPath)
    }

    @Test
    fun `withResolvedFilesystemDirectories should keep absolute filesystem directory unchanged`(@TempDir tempDir: File) {
        val workingDir = tempDir.resolve("working").apply { mkdirs() }.canonicalFile
        val absoluteDir = tempDir.resolve("absolute/specs").apply { mkdirs() }.canonicalFile
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = absoluteDir.path))

        val resolved = source.withCanonicalizedSources(workingDir)

        assertThat(resolved.getFileSystem()?.directory).isEqualTo(absoluteDir.canonicalPath)
    }
}
