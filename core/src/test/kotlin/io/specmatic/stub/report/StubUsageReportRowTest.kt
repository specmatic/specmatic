package io.specmatic.stub.report

import io.specmatic.core.SourceProvider
import io.specmatic.reports.OpenApiOperationGroup
import io.specmatic.reports.ReportItem
import io.specmatic.reports.ReportMetadata
import io.specmatic.reports.ServiceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StubUsageReportRowTest {
    private val operation1 = OpenApiOperationGroup(path = "/api/test1", method = "GET", responseCode = 200, count = 5)
    private val operation2 = OpenApiOperationGroup(path = "/api/test2", method = "POST", responseCode = 201, count = 10)
    private val operation3 = OpenApiOperationGroup(path = "/api/test1", method = "GET", responseCode = 200, count = 2)

    @Test
    fun `hasSameRowIdentifiers should return true for matching identifiers`() {
        val row1 =
            reportItem(
                type = "git",
                repository = "repo1",
                branch = "main",
                specification = "spec1",
                operations = listOf(operation1),
            )
        val row2 =
            reportItem(
                type = "git",
                repository = "repo1",
                branch = "main",
                specification = "spec1",
                operations = listOf(operation2),
            )

        val result = row1.equalsByIdentity(row2)
        assertTrue(result)
    }

    @Test
    fun `hasSameRowIdentifiers should return false for non-matching identifiers`() {
        val row1 =
            reportItem(
                type = "git",
                repository = "repo1",
                branch = "main",
                specification = "spec1",
                operations = listOf(operation1),
            )
        val row2 =
            reportItem(
                type = "filesystem",
                specification = "spec1",
                operations = listOf(operation2),
            )

        val result = row1.equalsByIdentity(row2)
        assertFalse(result)
    }

    @Test
    fun `merge should combine operations of two rows with unique operations`() {
        val row1 =
            reportItem(
                type = "git",
                repository = "repo1",
                branch = "main",
                specification = "spec1",
                operations = listOf(operation1),
            )
        val row2 =
            reportItem(
                type = "git",
                repository = "repo1",
                branch = "main",
                specification = "spec1",
                operations = listOf(operation2),
            )

        val mergedRow = row1.merge(row2)
        assertThat(mergedRow.operations).hasSize(2)
        assertThat(mergedRow.operations).containsExactlyInAnyOrder(operation1, operation2)
    }

    @Test
    fun `merge should combine operations of two rows with overlapping operations`() {
        val row1 =
            reportItem(
                type = "git",
                repository = "repo1",
                branch = "main",
                specification = "spec1",
                operations = listOf(operation1),
            )
        val row2 =
            reportItem(
                type = "git",
                repository = "repo1",
                branch = "main",
                specification = "spec1",
                operations = listOf(operation3),
            )

        val mergedRow = row1.merge(row2)
        assertThat(mergedRow.operations).hasSize(1)
        assertThat(mergedRow.operations[0].count).isEqualTo(7)
    }

    private fun reportItem(
        type: String,
        repository: String? = null,
        branch: String? = null,
        specification: String,
        operations: List<OpenApiOperationGroup>,
    ): ReportItem =
        ReportItem(
            metadata =
                ReportMetadata(
                    sourceProvider = SourceProvider.fromString(type),
                    sourceRepository = repository,
                    sourceRepositoryBranch = branch,
                    specification = specification,
                    serviceType = ServiceType.HTTP,
                ),
            operations = operations,
        )
}
