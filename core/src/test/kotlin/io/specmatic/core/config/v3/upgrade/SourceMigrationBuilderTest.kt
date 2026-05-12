package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.sources.GitAuthentication
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceMigrationBuilderTest {
    @Test
    fun `build test migrations keeps first spec type and latest partial base path`() {
        val source = Source(
            provider = SourceProvider.git,
            repository = "https://github.com/specmatic/specs.git",
            branch = "main",
            test = listOf(
                SpecExecutionConfig.ObjectValue.PartialUrl(specs = listOf("api.yaml"), basePath = "/v1"),
                SpecExecutionConfig.ObjectValue.PartialUrl(specs = listOf("api.yaml"), basePath = "/v2"),
            ),
        )

        val migration = SourceMigrationBuilder(GitAuthentication.BearerEnv("TOKEN"))
            .buildTestMigrations(listOf(source))
            .single()

        assertThat(migration.specTypesByPath).containsEntry("api.yaml", SpecType.OPENAPI)

        val spec = migration.definition.definition.specs.single() as SpecificationDefinition.ObjectValue
        assertThat(spec.spec.id).isEqualTo("api.yaml")
        assertThat(spec.spec.urlPathPrefix).isEqualTo("/v2")

        val sourceV3 = (migration.definition.definition.source as RefOrValue.Value).value
        assertThat(sourceV3.getGit()?.auth).isEqualTo(GitAuthentication.BearerEnv("TOKEN"))
    }

    @Test
    fun `build mock migrations creates one migration per stub config`() {
        val source = Source(
            provider = SourceProvider.filesystem,
            directory = "./specs",
            stub = listOf(SpecExecutionConfig.StringValue("a.yaml"), SpecExecutionConfig.StringValue("b.yaml"),)
        )

        val migrations = SourceMigrationBuilder(null).buildMockMigrations(listOf(source))
        assertThat(migrations).hasSize(2)
        assertThat(migrations.map { it.specTypesByPath.keys.single() }).containsExactly("a.yaml", "b.yaml")
    }
}
