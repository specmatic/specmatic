package io.specmatic.core.config.v3

import io.specmatic.core.TEST_FILTER_PROPERTY
import io.specmatic.core.TEST_OVERLAY_FILE_PATH_PROPERTY
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.MockServiceConfig
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.services.TestServiceConfig
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SpecmaticConfigV3ImplRegressionTest {
    @Test
    fun `stubSpecPathFromConfigFor should resolve stub spec from dependencies`(@TempDir tempDir: File) {
        val stubFile = tempDir.resolve("stub-contract.yaml").apply { writeText("openapi: 3.0.0") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))

        val dependencies = MockServiceConfig(
            services = listOf(
                MockServiceConfig.Value(
                    service = RefOrValue.Value(
                        CommonServiceConfig<MockRunOptions, MockSettings>(
                            definitions = listOf(
                                Definition(
                                    Definition.Value(
                                        source = RefOrValue.Value(source),
                                        specs = listOf(SpecificationDefinition.StringValue(stubFile.name))
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            settings = null
        )

        val config = SpecmaticConfigV3Impl(
            file = tempDir.resolve("specmatic.yaml"),
            specmaticConfig = SpecmaticConfigV3(
                version = SpecmaticConfigVersion.VERSION_3,
                dependencies = dependencies
            )
        )

        assertThat(config.stubSpecPathFromConfigFor(stubFile.canonicalPath)).isEqualTo(stubFile.name)
    }

    @Test
    fun `getTestFilter should fallback to system property when openapi test run options are absent`(@TempDir tempDir: File) {
        val original = System.getProperty(TEST_FILTER_PROPERTY)
        System.setProperty(TEST_FILTER_PROPERTY, "status==204")
        try {
            val config = SpecmaticConfigV3Impl(
                file = tempDir.resolve("specmatic.yaml"),
                specmaticConfig = SpecmaticConfigV3(version = SpecmaticConfigVersion.VERSION_3)
            )

            assertThat(config.getTestFilter()).isEqualTo("status==204")
        } finally {
            if (original == null) System.clearProperty(TEST_FILTER_PROPERTY) else System.setProperty(TEST_FILTER_PROPERTY, original)
        }
    }

    @Test
    fun `getTestOverlayFilePath should fallback to system property when run options are absent`(@TempDir tempDir: File) {
        val original = System.getProperty(TEST_OVERLAY_FILE_PATH_PROPERTY)
        System.setProperty(TEST_OVERLAY_FILE_PATH_PROPERTY, "overlay-from-property.yaml")
        try {
            val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))
            val specFile = tempDir.resolve("sut-contract.yaml").apply { writeText("openapi: 3.0.0") }

            val sut = TestServiceConfig(
                service = RefOrValue.Value(
                    CommonServiceConfig<TestRunOptions, TestSettings>(
                        definitions = listOf(
                            Definition(
                                Definition.Value(
                                    source = RefOrValue.Value(source),
                                    specs = listOf(SpecificationDefinition.StringValue(specFile.name))
                                )
                            )
                        )
                    )
                )
            )

            val config = SpecmaticConfigV3Impl(
                file = tempDir.resolve("specmatic.yaml"),
                specmaticConfig = SpecmaticConfigV3(
                    version = SpecmaticConfigVersion.VERSION_3,
                    systemUnderTest = sut
                )
            )

            assertThat(config.getTestOverlayFilePath(specFile, SpecType.OPENAPI)).isEqualTo("overlay-from-property.yaml")
        } finally {
            if (original == null) System.clearProperty(TEST_OVERLAY_FILE_PATH_PROPERTY) else System.setProperty(TEST_OVERLAY_FILE_PATH_PROPERTY, original)
        }
    }

    @Test
    fun `getStubOverlayFilePath should fallback to system property when run options are absent`(@TempDir tempDir: File) {
        val original = System.getProperty(TEST_OVERLAY_FILE_PATH_PROPERTY)
        System.setProperty(TEST_OVERLAY_FILE_PATH_PROPERTY, "overlay-from-property.yaml")
        try {
            val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))
            val specFile = tempDir.resolve("stub-contract.yaml").apply { writeText("openapi: 3.0.0") }

            val dependencies = MockServiceConfig(
                services = listOf(
                    MockServiceConfig.Value(
                        service = RefOrValue.Value(
                            CommonServiceConfig<MockRunOptions, MockSettings>(
                                definitions = listOf(
                                    Definition(
                                        Definition.Value(
                                            source = RefOrValue.Value(source),
                                            specs = listOf(SpecificationDefinition.StringValue(specFile.name))
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                settings = null
            )

            val config = SpecmaticConfigV3Impl(
                file = tempDir.resolve("specmatic.yaml"),
                specmaticConfig = SpecmaticConfigV3(
                    version = SpecmaticConfigVersion.VERSION_3,
                    dependencies = dependencies
                )
            )

            assertThat(config.getStubOverlayFilePath(specFile, SpecType.OPENAPI)).isEqualTo("overlay-from-property.yaml")
        } finally {
            if (original == null) System.clearProperty(TEST_OVERLAY_FILE_PATH_PROPERTY) else System.setProperty(TEST_OVERLAY_FILE_PATH_PROPERTY, original)
        }
    }
}
