package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SourceTest {
    @Test
    fun `withResolvedFilesystemDirectory should resolve relative filesystem directory against working directory`(@TempDir tempDir: File) {
        val workingDir = tempDir.resolve("working").apply { mkdirs() }.canonicalFile
        val source = Source(provider = SourceProvider.filesystem, directory = "./specs")

        val resolved = source.withResolvedFilesystemDirectory(workingDir)

        assertThat(resolved.directory).isEqualTo(workingDir.resolve("specs").canonicalPath)
    }

    @Test
    fun `withResolvedFilesystemDirectory should keep source unchanged when provider is not filesystem`(@TempDir tempDir: File) {
        val source = Source(provider = SourceProvider.git, directory = "./specs")

        val resolved = source.withResolvedFilesystemDirectory(tempDir)

        assertThat(resolved).isEqualTo(source)
    }

    @Test
    fun `withResolvedFilesystemDirectory should resolve default filesystem directory against working directory`(@TempDir tempDir: File) {
        val workingDir = tempDir.resolve("working").apply { mkdirs() }.canonicalFile
        val source = Source(provider = SourceProvider.filesystem)

        val resolved = source.withResolvedFilesystemDirectory(workingDir)

        assertThat(resolved.directory).isEqualTo(workingDir.canonicalPath)
    }

    @Test
    fun `withResolvedFilesystemDirectory should keep absolute filesystem directory unchanged`(@TempDir tempDir: File) {
        val workingDir = tempDir.resolve("working").apply { mkdirs() }.canonicalFile
        val absoluteDir = tempDir.resolve("absolute/specs").apply { mkdirs() }.canonicalFile
        val source = Source(provider = SourceProvider.filesystem, directory = absoluteDir.path)

        val resolved = source.withResolvedFilesystemDirectory(workingDir)

        assertThat(resolved.directory).isEqualTo(absoluteDir.canonicalPath)
    }
}
