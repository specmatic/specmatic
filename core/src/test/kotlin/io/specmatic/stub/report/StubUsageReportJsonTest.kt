package io.specmatic.stub.report

import org.assertj.core.api.AssertionsForClassTypes.assertThat
import org.junit.jupiter.api.Test

class StubUsageReportJsonTest {

    @Test
    fun `append an empty existing report does not change the new report`() {
        val newReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = emptyList()
        )

        val expectedMergedReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    @Test
    fun `append an new report with existing report with additional counts sums the count`() {
        val newReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 200, 2)
                    )
                )
            )
        )

        val expectedMergedReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 200, 3)
                    )
                )
            )
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    @Test
    fun `adds separate operation when existing report contains another path for the same spec`() {
        val newReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path2", "GET", 200, 2)
                    )
                )
            )
        )

        val expectedMergedReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path2", "GET", 200, 2),
                        StubUsageReportOperation("/path1", "GET", 200, 1)
                    )
                ),
            )
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    @Test
    fun `add separate row when existing report contains another path for the different spec`() {
        val newReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api2.yaml", listOf(
                        StubUsageReportOperation("/path2", "GET", 200, 2)
                    )
                )
            )
        )

        val expectedMergedReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api2.yaml", listOf(
                        StubUsageReportOperation("/path2", "GET", 200, 2),
                    )
                ),
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 200, 1)
                    )
                ),
            )
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    @Test
    fun `add separate operation when response code is different in existing report`() {
        val newReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 404, 2)
                    )
                )
            )
        )

        val expectedMergedReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 404, 2),
                        StubUsageReportOperation("/path1", "GET", 200, 1),
                    )
                ),
            )
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    @Test
    fun `add separate operation when method is different in existing report`() {
        val newReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "POST", 200, 2)
                    )
                )
            )
        )

        val expectedMergedReport = StubUsageReportJson(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportRow(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        StubUsageReportOperation("/path1", "POST", 200, 2),
                        StubUsageReportOperation("/path1", "GET", 200, 1),
                    )
                ),
            )
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    private fun stubUsageReportRow(
        specification: String, stubUsageReportOperations: List<StubUsageReportOperation>
    ): StubUsageReportRow {
        return StubUsageReportRow(
            type = "git",
            repository = "https://github.com/specmatic/specmatic-order-contracts.git",
            branch = "main",
            specification = specification,
            serviceType = "HTTP",
            operations = stubUsageReportOperations
        )
    }
}