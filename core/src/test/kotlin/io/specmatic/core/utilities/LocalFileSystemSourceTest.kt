package io.specmatic.core.utilities

import io.specmatic.toContractSourceEntries
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalFileSystemSourceTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `filesystem source loadContracts resolves spec paths relative to the git repo root when the source directory is the specmatic yaml directory`() {
        val repoRoot = tempDir.resolve("repo-config-dir").apply { mkdirs() }
        runGit(repoRoot, "init")
        val specFile =
            repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                parentFile.mkdirs()
                writeText("openapi: 3.0.0\ninfo:\n  title: Orders\n  version: '1'\npaths: {}\n")
            }
        val configFile = repoRoot.resolve("specmatic.yaml").apply {
            writeText("version: 3")
        }

        val source =
            LocalFileSystemSource(
                directory = repoRoot.canonicalPath,
                testContracts = emptyList(),
                stubContracts = listOf("specs/openapi/order_api.yaml").toContractSourceEntries(),
            )

        val contractPathData =
            source.loadContracts({ it.stubContracts }, repoRoot.canonicalPath, configFile.canonicalPath).single()

        assertThat(contractPathData.path).isEqualTo(specFile.canonicalPath)
        assertThat(contractPathData.specificationPath).isEqualTo("specs/openapi/order_api.yaml")
    }

    @Test
    fun `filesystem source loadContracts resolves spec paths relative to the git repo root when the source directory is explicitly set`() {
        val repoRoot = tempDir.resolve("repo-explicit-dir").apply { mkdirs() }
        runGit(repoRoot, "init")
        val specFile =
            repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                parentFile.mkdirs()
                writeText("openapi: 3.0.0\ninfo:\n  title: Orders\n  version: '1'\npaths: {}\n")
            }
        val configFile = repoRoot.resolve("specmatic.yaml").apply {
            writeText("version: 3")
        }

        val source =
            LocalFileSystemSource(
                directory = repoRoot.resolve("specs").canonicalPath,
                testContracts = emptyList(),
                stubContracts = listOf("openapi/order_api.yaml").toContractSourceEntries(),
            )

        val contractPathData =
            source.loadContracts({ it.stubContracts }, repoRoot.canonicalPath, configFile.canonicalPath).single()

        assertThat(contractPathData.path).isEqualTo(specFile.canonicalPath)
        assertThat(contractPathData.specificationPath).isEqualTo("specs/openapi/order_api.yaml")
    }

    private fun runGit(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + args).start()
        val exitCode = process.waitFor()
        assertThat(exitCode).isZero()
    }
}
