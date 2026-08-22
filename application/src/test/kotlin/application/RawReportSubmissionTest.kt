package application

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.specmatic.core.git.SystemGit
import io.specmatic.reporter.RawReportSender
import io.specmatic.reporter.RawReportType
import io.specmatic.reporter.commands.InsightsReportOptions
import io.specmatic.test.sendRawReportToInsights
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RawReportSubmissionTest {
    @Test
    fun `forwards all Insights report options to the sender`(@TempDir tempDir: File) {
        val report = tempDir.resolve("ctrf-report.json").apply { writeText("{}") }
        val options = InsightsReportOptions().apply {
            ci = true
            buildId = "build-42"
            repoId = "repo-id"
            repoName = "repo-name"
            repoUrl = "https://example.com/repo.git"
            branchName = "main"
            token = "token"
        }
        val git = mockk<SystemGit>()
        mockkObject(RawReportSender)
        every { RawReportSender.send(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true

        try {
            sendRawReportToInsights(report, RawReportType.TEST, options, git)

            verify(exactly = 1) {
                RawReportSender.send(
                    reportFile = report,
                    reportType = RawReportType.TEST,
                    ci = true,
                    buildId = "build-42",
                    repoId = "repo-id",
                    repoName = "repo-name",
                    repoUrl = "https://example.com/repo.git",
                    branchName = "main",
                    token = "token",
                )
            }
        } finally {
            unmockkObject(RawReportSender)
        }
    }
}
