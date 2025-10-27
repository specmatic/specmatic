package io.specmatic.stub.report

import io.specmatic.core.SourceProvider
import io.specmatic.reports.OpenApiOperationGroup
import io.specmatic.reports.ReportItem
import io.specmatic.reports.ReportMetadata
import io.specmatic.reports.ServiceType
import io.specmatic.reports.StubUsageReport
import org.assertj.core.api.AssertionsForClassTypes.assertThat
import org.junit.jupiter.api.Test

class StubUsageReportTest {

    @Test
    fun `append an empty existing report does not change the new report`() {
        val newReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = emptyList()
        )

        val expectedMergedReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    @Test
    fun `append an new report with existing report with additional counts sums the count`() {
        val newReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 2)
                    )
                )
            )
        )

        val expectedMergedReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 3)
                    )
                )
            )
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    @Test
    fun `adds separate operation when existing report contains another path for the same spec`() {
        val newReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path2", "GET", 200, 2)
                    )
                )
            )
        )

        val expectedMergedReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 1),
                        OpenApiOperationGroup("/path2", "GET", 200, 2),
                    ),
                ),
            ),
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    @Test
    fun `add separate row when existing report contains another path for the different spec`() {
        val newReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api2.yaml", listOf(
                        OpenApiOperationGroup("/path2", "GET", 200, 2)
                    )
                )
            )
        )

        val expectedMergedReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 1)
                    ),
                ),
                stubUsageReportItem(
                    "in/specmatic/examples/store/api2.yaml", listOf(
                        OpenApiOperationGroup("/path2", "GET", 200, 2),
                    )
                ),
            ),
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    @Test
    fun `add separate operation when response code is different in existing report`() {
        val newReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 404, 2)
                    )
                )
            )
        )

        val expectedMergedReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 1),
                        OpenApiOperationGroup("/path1", "GET", 404, 2),
                    )
                ),
            )
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    @Test
    fun `add separate operation when method is different in existing report`() {
        val newReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 1)
                    )
                )
            )
        )

        val existingReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "POST", 200, 2)
                    )
                )
            )
        )

        val expectedMergedReport = StubUsageReport(
            specmaticConfigPath = "./specmatic.yaml",
            stubUsage = listOf(
                stubUsageReportItem(
                    "in/specmatic/examples/store/api1.yaml", listOf(
                        OpenApiOperationGroup("/path1", "GET", 200, 1),
                        OpenApiOperationGroup("/path1", "POST", 200, 2),
                    )
                ),
            )
        )

        val mergedReport = newReport.merge(existingReport)

        assertThat(mergedReport).isEqualTo(expectedMergedReport)
    }

    private fun stubUsageReportItem(
        specification: String, stubUsageReportOperations: List<OpenApiOperationGroup>
    ): ReportItem {
        return ReportItem(
            metadata = ReportMetadata(
                sourceProvider = SourceProvider.git,
                sourceRepository = "https://github.com/specmatic/specmatic-order-contracts.git",
                sourceRepositoryBranch = "main",
                specification = specification,
                serviceType = ServiceType.HTTP,
            ),
            operations = stubUsageReportOperations,
        )
    }
}