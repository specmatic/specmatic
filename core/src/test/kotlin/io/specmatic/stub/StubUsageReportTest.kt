package io.specmatic.stub

import io.specmatic.reporter.generated.dto.stub.usage.HTTPStubUsageOperation
import io.specmatic.reporter.generated.dto.stub.usage.SpecmaticStubUsageReport
import io.specmatic.reporter.generated.dto.stub.usage.StubUsageEntry
import io.specmatic.stub.report.StubEndpoint
import io.specmatic.stub.report.StubUsageReport
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class StubUsageReportTest {

    companion object {
        const val CONFIG_FILE_PATH = "./specmatic.json"
    }

    @Test
    fun `test generates stub usage report based on stub request logs`() {
        val allEndpoints = mutableListOf(
            StubEndpoint("/route1", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("/route1", "POST", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("/route2", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route2.yaml", "HTTP"),
            StubEndpoint("/route2", "POST", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route2.yaml", "HTTP"),
        )

        val stubLogs = mutableListOf(
            StubEndpoint("/route1", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("/route1", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("/route1", "POST", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("/route1", "POST", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            StubEndpoint("/route2", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route2.yaml", "HTTP"),
            StubEndpoint("/route2", "GET", 200, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route2.yaml", "HTTP"),
        )

        val stubUsageJsonReport = StubUsageReport(CONFIG_FILE_PATH, allEndpoints, stubLogs).generate()
        Assertions.assertThat(stubUsageJsonReport).isEqualTo(
            SpecmaticStubUsageReport(
                CONFIG_FILE_PATH, listOf(
                    StubUsageEntry(
                        "git",
                        "https://github.com/specmatic/specmatic-order-contracts.git",
                        "in/specmatic/examples/store/route1.yaml",
                        "main",
                        "HTTP",
                        "OPENAPI",
                        listOf(
                            HTTPStubUsageOperation("/route1", "GET",200, 2),
                            HTTPStubUsageOperation( "/route1", "POST",200, 2)
                        )
                    ),
                    StubUsageEntry(
                        "git",
                        "https://github.com/specmatic/specmatic-order-contracts.git",
                        "in/specmatic/examples/store/route2.yaml",
                        "main",
                        "HTTP",
                        "OPENAPI",
                        listOf(
                            HTTPStubUsageOperation( "/route2", "GET",200, 2),
                            HTTPStubUsageOperation( "/route2", "POST",200, 0)
                        )
                    )
                )
            )
        )
    }
}