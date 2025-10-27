package io.specmatic.stub

import io.specmatic.core.SourceProvider
import io.specmatic.reports.OpenApiOperationGroup
import io.specmatic.reports.OpenApiStubUsageStrategy
import io.specmatic.reports.ReportGenerator
import io.specmatic.reports.ReportItem
import io.specmatic.reports.ReportMetadata
import io.specmatic.reports.ServiceType
import io.specmatic.reports.StubUsageReport
import io.specmatic.stub.report.*
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class StubUsageReportTest {

    companion object {
        const val CONFIG_FILE_PATH = "./specmatic.json"
    }

    @Test
    fun `test generates stub usage report based on stub request logs`() {
        val allEndpoints = mutableListOf(
            StubEndpoint("", "/route1", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("", "/route1", "POST", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("", "/route2", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route2.yaml", "HTTP"),
            StubEndpoint("", "/route2", "POST", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route2.yaml", "HTTP"),
        )

        val stubLogs = mutableListOf(
            StubEndpoint("", "/route1", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("", "/route1", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("", "/route1", "POST", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("", "/route1", "POST", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("", "/route2", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route2.yaml", "HTTP"),
            StubEndpoint("", "/route2", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route2.yaml", "HTTP"),
        )

        val stubUsageReportGenerator = ReportGenerator.createStubUsageReportGenerator(OpenApiStubUsageStrategy())
        val stubUsageJsonReport = stubUsageReportGenerator.generate(CONFIG_FILE_PATH, allEndpoints, stubLogs)

        Assertions.assertThat(stubUsageJsonReport).isEqualTo(
            StubUsageReport(
                specmaticConfigPath = CONFIG_FILE_PATH,
                stubUsage = listOf(
                    ReportItem(
                        metadata = ReportMetadata(
                            SourceProvider.git,
                            "https://github.com/specmatic/specmatic-order-contracts.git",
                            "main",
                            "in/specmatic/examples/store/route1.yaml",
                            ServiceType.HTTP,
                        ),
                        operations = listOf(
                            OpenApiOperationGroup("/route1", "GET",200, 2),
                            OpenApiOperationGroup("/route1", "POST",200, 2),
                        ),
                    ),
                    ReportItem(
                        metadata = ReportMetadata(
                            SourceProvider.git,
                            "https://github.com/specmatic/specmatic-order-contracts.git",
                            "main",
                            "in/specmatic/examples/store/route2.yaml",
                            ServiceType.HTTP,
                        ),
                        operations = listOf(
                            OpenApiOperationGroup("/route2", "GET",200, 2),
                            OpenApiOperationGroup("/route2", "POST",200, 0),
                        ),
                    ),
                ),
            ),
        )
    }
}