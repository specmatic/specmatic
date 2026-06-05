package io.specmatic.core

import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.SpecmaticConfigV3
import io.specmatic.core.config.v3.SpecmaticConfigV3Impl
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.services.TestServiceConfig
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.TestResultRecord.Companion.CONTRACT_TEST_TEST_TYPE
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CtrfSpecificationPathTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `parseContractFileToFeature normalizes filesystem specification path when spec is inside a git repo`() {
        val repoRoot = tempDir.resolve("repo").apply { mkdirs() }
        runGit(repoRoot, "init")
        val specFile =
            repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                parentFile.mkdirs()
                writeText(minimalOpenApi())
            }

        val feature =
            parseContractFileToFeature(
                file = specFile,
                sourceProvider = SourceProvider.filesystem.name,
                specificationPath = "openapi/order_api.yaml",
                specmaticConfig = SpecmaticConfig(),
            )

        assertThat(feature.specification).isEqualTo("specs/openapi/order_api.yaml")
        assertThat(feature.scenarios).isNotEmpty
        assertThat(feature.scenarios.mapNotNull { it.specification }.distinct()).containsExactly("specs/openapi/order_api.yaml")
    }

    @Test
    fun `getCtrfSpecConfig normalizes v3 filesystem spec path relative to git root`() {
        val repoRoot = tempDir.resolve("repo-v3").apply { mkdirs() }
        runGit(repoRoot, "init")
        val specFile =
            repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                parentFile.mkdirs()
                writeText(minimalOpenApi())
            }
        val configFile = repoRoot.resolve("test/resources/specmatic.yaml").apply {
            parentFile.mkdirs()
            writeText("version: 3")
        }
        val originalConfigPath = Configuration.configFilePath

        try {
            Configuration.configFilePath = configFile.canonicalPath
            val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = "../../specs"))
            val config =
                SpecmaticConfigV3Impl(
                    file = configFile,
                    specmaticConfig =
                        SpecmaticConfigV3(
                            version = SpecmaticConfigVersion.VERSION_3,
                            systemUnderTest =
                                TestServiceConfig(
                                    service =
                                        RefOrValue.Value(
                                            CommonServiceConfig<TestRunOptions, TestSettings>(
                                                definitions =
                                                    listOf(
                                                        Definition(
                                                            Definition.Value(
                                                                source = RefOrValue.Value(source),
                                                                specs = listOf(SpecificationDefinition.StringValue("openapi/order_api.yaml")),
                                                            ),
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                )

            val ctrfSpecConfig =
                config.getCtrfSpecConfig(
                    specFile = specFile,
                    testType = CONTRACT_TEST_TEST_TYPE,
                    protocol = "HTTP",
                    specType = SpecType.OPENAPI.value,
                )

            assertThat(ctrfSpecConfig.specification).isEqualTo("specs/openapi/order_api.yaml")
            assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.filesystem.name)
        } finally {
            Configuration.configFilePath = originalConfigPath
        }
    }

    @Test
    fun `getCtrfSpecConfig normalizes v1 filesystem spec path relative to git root`() {
        val repoRoot = tempDir.resolve("repo-v1").apply { mkdirs() }
        runGit(repoRoot, "init")
        val specFile =
            repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                parentFile.mkdirs()
                writeText(minimalOpenApi())
            }
        val configFile = repoRoot.resolve("test/resources/specmatic.yaml").apply {
            parentFile.mkdirs()
            writeText("version: 2")
        }
        val originalConfigPath = Configuration.configFilePath

        try {
            Configuration.configFilePath = configFile.canonicalPath
            val config =
                SpecmaticConfigV1V2Common(
                    sources =
                        listOf(
                            Source(
                                provider = SourceProvider.filesystem,
                                directory = "../../specs",
                                test = listOf(SpecExecutionConfig.StringValue("openapi/order_api.yaml")),
                            ),
                        ),
                )

            val ctrfSpecConfig =
                config.getCtrfSpecConfig(
                    specFile = specFile,
                    testType = CONTRACT_TEST_TEST_TYPE,
                    protocol = "HTTP",
                    specType = SpecType.OPENAPI.value,
                )

            assertThat(ctrfSpecConfig.specification).isEqualTo("specs/openapi/order_api.yaml")
            assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.filesystem.name)
        } finally {
            Configuration.configFilePath = originalConfigPath
        }
    }

    private fun runGit(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + args).start()
        val exitCode = process.waitFor()
        assertThat(exitCode).isZero()
    }

    private fun minimalOpenApi(): String =
        """
        openapi: 3.0.0
        info:
          title: Orders
          version: "1.0"
        paths:
          /orders:
            get:
              responses:
                "200":
                  description: OK
        """.trimIndent()
}
