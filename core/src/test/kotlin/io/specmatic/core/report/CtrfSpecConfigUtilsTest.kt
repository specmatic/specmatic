package io.specmatic.core.report

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.v3.SpecExecutionConfig
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord
import io.specmatic.test.TestResultRecord.Companion.CONTRACT_TEST_TEST_TYPE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CtrfSpecConfigUtilsTest {

    @Test
    fun `should return empty list when test result records is empty`() {
        val config = SpecmaticConfig()
        val result = ctrfSpecConfigsFrom(config, emptyList())

        assertThat(result).isEmpty()
    }

    @Test
    fun `should filter out records with null specification`() {
        val config = SpecmaticConfig()
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns null
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        assertThat(result).isEmpty()
    }

    @Test
    fun `should filter out records with empty specification`() {
        val config = SpecmaticConfig()
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns ""
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        assertThat(result).isEmpty()
    }

    @Test
    fun `should filter out records with blank specification`() {
        val config = SpecmaticConfig()
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns "   "
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        assertThat(result).isEmpty()
    }

    @Test
    fun `should filter out records when source is not found for contract test`() {
        val config = SpecmaticConfig(sources = emptyList())
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns "/path/to/api.yaml"
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        // When no matching source is found, specification becomes empty and gets filtered out
        assertThat(result).isEmpty()
    }

    @Test
    fun `should filter out records when source is not found for stub test`() {
        val config = SpecmaticConfig(sources = emptyList())
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns "/path/to/api.yaml"
            every { testType } returns "StubTest"
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        // When no matching source is found, specification becomes empty and gets filtered out
        assertThat(result).isEmpty()
    }

    @Test
    fun `should find test source from config for contract test`() {
        val testSpec = "com/petstore/api.yaml"
        val config = SpecmaticConfig(
            sources = listOf(
                Source(
                    provider = SourceProvider.git,
                    repository = "https://github.com/test/contracts",
                    branch = "develop",
                    test = listOf(testSpec)
                )
            )
        )
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns "/home/user/contracts/$testSpec"
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        assertThat(result).hasSize(1)
        assertThat(result[0].serviceType).isEqualTo("HTTP")
        assertThat(result[0].specType).isEqualTo("OPENAPI")
        assertThat(result[0].specification).isEqualTo(testSpec)
        assertThat(result[0].sourceProvider).isEqualTo("git")
        assertThat(result[0].repository).isEqualTo("https://github.com/test/contracts")
        assertThat(result[0].branch).isEqualTo("develop")
    }

    @Test
    fun `should find stub source from config with string value for non-contract test`() {
        val stubSpec = "com/petstore/payment.yaml"
        val config = SpecmaticConfig(
            sources = listOf(
                Source(
                    provider = SourceProvider.git,
                    repository = "https://github.com/test/contracts",
                    branch = "feature-branch",
                    stub = listOf(SpecExecutionConfig.StringValue(stubSpec))
                )
            )
        )
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns "/workspace/contracts/$stubSpec"
            every { testType } returns "StubTest"
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        assertThat(result).hasSize(1)
        assertThat(result[0].serviceType).isEqualTo("HTTP")
        assertThat(result[0].specType).isEqualTo("OPENAPI")
        assertThat(result[0].specification).isEqualTo(stubSpec)
        assertThat(result[0].sourceProvider).isEqualTo("git")
        assertThat(result[0].repository).isEqualTo("https://github.com/test/contracts")
        assertThat(result[0].branch).isEqualTo("feature-branch")
    }

    @Test
    fun `should find stub source from config with object value for non-contract test`() {
        val stubSpec = "com/payment/api.yaml"
        val config = SpecmaticConfig(
            sources = listOf(
                Source(
                    provider = SourceProvider.filesystem,
                    directory = "/local/contracts",
                    stub = listOf(
                        SpecExecutionConfig.ObjectValue.FullUrl(
                            specs = listOf(stubSpec),
                            baseUrl = "http://localhost:8080"
                        )
                    )
                )
            )
        )
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns "/local/contracts/$stubSpec"
            every { testType } returns "Integration"
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        assertThat(result).hasSize(1)
        assertThat(result[0].serviceType).isEqualTo("HTTP")
        assertThat(result[0].specType).isEqualTo("OPENAPI")
        assertThat(result[0].specification).isEqualTo(stubSpec)
        assertThat(result[0].sourceProvider).isEqualTo("filesystem")
        assertThat(result[0].repository).isEmpty()
        assertThat(result[0].branch).isEqualTo("main")
    }

    @Test
    fun `should use custom service type and spec type when provided`() {
        val testSpec = "com/grpc/service.proto"
        val config = SpecmaticConfig(
            sources = listOf(
                Source(
                    provider = SourceProvider.web,
                    test = listOf(testSpec)
                )
            )
        )
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns "https://api.example.com/$testSpec"
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record), serviceType = "GRPC", specType = "PROTO")

        assertThat(result).hasSize(1)
        assertThat(result[0].serviceType).isEqualTo("GRPC")
        assertThat(result[0].specType).isEqualTo("PROTO")
        assertThat(result[0].specification).isEqualTo(testSpec)
        assertThat(result[0].sourceProvider).isEqualTo("web")
    }

    @Test
    fun `should handle multiple test result records with mixed specifications`() {
        val testSpec1 = "api/users.yaml"
        val testSpec2 = "api/products.yaml"
        val config = SpecmaticConfig(
            sources = listOf(
                Source(
                    provider = SourceProvider.git,
                    repository = "https://github.com/test/repo",
                    branch = "main",
                    test = listOf(testSpec1, testSpec2)
                )
            )
        )

        val record1 = mockk<CtrfTestResultRecord> {
            every { specification } returns "/project/$testSpec1"
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }
        val record2 = mockk<CtrfTestResultRecord> {
            every { specification } returns "/project/$testSpec2"
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }
        val record3 = mockk<CtrfTestResultRecord> {
            every { specification } returns ""
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record1, record2, record3))

        assertThat(result).hasSize(2)
        assertThat(result[0].specification).isEqualTo(testSpec1)
        assertThat(result[1].specification).isEqualTo(testSpec2)
    }

    @Test
    fun `should use null branch as main when source has no branch specified`() {
        val testSpec = "api/test.yaml"
        val config = SpecmaticConfig(
            sources = listOf(
                Source(
                    provider = SourceProvider.git,
                    repository = "https://github.com/test/repo",
                    branch = null,
                    test = listOf(testSpec)
                )
            )
        )
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns "/path/$testSpec"
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        assertThat(result).hasSize(1)
        assertThat(result[0].branch).isEqualTo("main")
    }

    @Test
    fun `should handle multiple sources and find correct one for contract test`() {
        val testSpec1 = "api/users.yaml"
        val testSpec2 = "api/orders.yaml"
        val config = SpecmaticConfig(
            sources = listOf(
                Source(
                    provider = SourceProvider.git,
                    repository = "https://github.com/repo1",
                    branch = "branch1",
                    test = listOf(testSpec1)
                ),
                Source(
                    provider = SourceProvider.git,
                    repository = "https://github.com/repo2",
                    branch = "branch2",
                    test = listOf(testSpec2)
                )
            )
        )
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns "/project/$testSpec2"
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        assertThat(result).hasSize(1)
        assertThat(result[0].specification).isEqualTo(testSpec2)
        assertThat(result[0].repository).isEqualTo("https://github.com/repo2")
        assertThat(result[0].branch).isEqualTo("branch2")
    }

    @Test
    fun `should handle multiple sources and find correct one for stub test`() {
        val stubSpec1 = "api/payment.yaml"
        val stubSpec2 = "api/auth.yaml"
        val config = SpecmaticConfig(
            sources = listOf(
                Source(
                    provider = SourceProvider.filesystem,
                    directory = "/dir1",
                    stub = listOf(SpecExecutionConfig.StringValue(stubSpec1))
                ),
                Source(
                    provider = SourceProvider.git,
                    repository = "https://github.com/auth-repo",
                    branch = "auth-branch",
                    stub = listOf(SpecExecutionConfig.StringValue(stubSpec2))
                )
            )
        )
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns "/workspace/$stubSpec2"
            every { testType } returns "StubTest"
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        assertThat(result).hasSize(1)
        assertThat(result[0].specification).isEqualTo(stubSpec2)
        assertThat(result[0].repository).isEqualTo("https://github.com/auth-repo")
        assertThat(result[0].branch).isEqualTo("auth-branch")
        assertThat(result[0].sourceProvider).isEqualTo("git")
    }

    @Test
    fun `should handle source with null repository`() {
        val testSpec = "api/test.yaml"
        val config = SpecmaticConfig(
            sources = listOf(
                Source(
                    provider = SourceProvider.filesystem,
                    repository = null,
                    test = listOf(testSpec)
                )
            )
        )
        val record = mockk<CtrfTestResultRecord> {
            every { specification } returns "/path/$testSpec"
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record))

        assertThat(result).hasSize(1)
        assertThat(result[0].repository).isEmpty()
    }

    @Test
    fun `should process records with same specification but different test types`() {
        val spec = "api/common.yaml"
        val config = SpecmaticConfig(
            sources = listOf(
                Source(
                    provider = SourceProvider.git,
                    repository = "https://github.com/test/repo",
                    branch = "main",
                    test = listOf(spec),
                    stub = listOf(SpecExecutionConfig.StringValue(spec))
                )
            )
        )

        val record1 = mockk<CtrfTestResultRecord> {
            every { specification } returns "/path/$spec"
            every { testType } returns CONTRACT_TEST_TEST_TYPE
        }
        val record2 = mockk<CtrfTestResultRecord> {
            every { specification } returns "/path/$spec"
            every { testType } returns "StubTest"
        }

        val result = ctrfSpecConfigsFrom(config, listOf(record1, record2))

        assertThat(result).hasSize(2)
        assertThat(result[0].specification).isEqualTo(spec)
        assertThat(result[1].specification).isEqualTo(spec)
    }
}