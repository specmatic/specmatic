package io.specmatic.stub

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.generated.dto.stub.usage.HTTPStubUsageOperation
import io.specmatic.reporter.generated.dto.stub.usage.SpecmaticStubUsageReport
import io.specmatic.reporter.generated.dto.stub.usage.StubUsageEntry
import io.specmatic.reporter.model.SpecType
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
            stubEndpoint("/route1", "GET", 200, "in/specmatic/examples/store/route1.yaml"),
            stubEndpoint("/route1", "POST", 200, "in/specmatic/examples/store/route1.yaml"),
            stubEndpoint("/route2", "GET", 200, "in/specmatic/examples/store/route2.yaml"),
            stubEndpoint("/route2", "POST", 200, "in/specmatic/examples/store/route2.yaml"),
        )

        val stubLogs = mutableListOf(
            stubEndpoint("/route1", "GET", 200, "in/specmatic/examples/store/route1.yaml"),
            stubEndpoint("/route1", "GET", 200, "in/specmatic/examples/store/route1.yaml"),
            stubEndpoint("/route1", "POST", 200, "in/specmatic/examples/store/route1.yaml"),
            stubEndpoint("/route1", "POST", 200, "in/specmatic/examples/store/route1.yaml"),
            stubEndpoint("/route2", "GET", 200, "in/specmatic/examples/store/route2.yaml"),
            stubEndpoint("/route2", "GET", 200, "in/specmatic/examples/store/route2.yaml"),
        )

        val stubUsageJsonReport = StubUsageReport(CONFIG_FILE_PATH, allEndpoints, stubLogs).generate()

        Assertions.assertThat(stubUsageJsonReport).isEqualTo(
            SpecmaticStubUsageReport()
                .withSpecmaticConfigPath(CONFIG_FILE_PATH)
                .withStubUsage(
                    listOf(
                        StubUsageEntry()
                            .withType("git")
                            .withRepository("https://github.com/specmatic/specmatic-order-contracts.git")
                            .withSpecification("in/specmatic/examples/store/route1.yaml")
                            .withBranch("main")
                            .withServiceType("HTTP")
                            .withSpecType("OPENAPI")
                            .withOperations(
                                listOf(
                                    HTTPStubUsageOperation()
                                        .withPath("/route1")
                                        .withMethod("GET")
                                        .withResponseCode(200)
                                        .withCount(2),
                                    HTTPStubUsageOperation()
                                        .withPath("/route1")
                                        .withMethod("POST")
                                        .withResponseCode(200)
                                        .withCount(2)
                                )
                            ),
                        StubUsageEntry()
                            .withType("git")
                            .withRepository("https://github.com/specmatic/specmatic-order-contracts.git")
                            .withSpecification("in/specmatic/examples/store/route2.yaml")
                            .withBranch("main")
                            .withServiceType("HTTP")
                            .withSpecType("OPENAPI")
                            .withOperations(
                                listOf(
                                    HTTPStubUsageOperation()
                                        .withPath("/route2")
                                        .withMethod("GET")
                                        .withResponseCode(200)
                                        .withCount(2),
                                    HTTPStubUsageOperation()
                                        .withPath("/route2")
                                        .withMethod("POST")
                                        .withResponseCode(200)
                                        .withCount(0)
                                )
                            )
                    )
                )
        )
    }

    private fun stubEndpoint(path: String, method: String, responseCode: Int, specification: String): StubEndpoint {
        return StubEndpoint(
            path = path,
            method = method,
            responseCode = responseCode,
            sourceProvider = "git",
            sourceRepository = "https://github.com/specmatic/specmatic-order-contracts.git",
            sourceRepositoryBranch = "main",
            specification = specification,
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI,
        )
    }
}
