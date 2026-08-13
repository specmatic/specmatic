package io.specmatic.test

import io.specmatic.core.git.SystemGit
import io.specmatic.core.log.logger
import io.specmatic.reporter.RawReportSender
import io.specmatic.reporter.RawReportType
import io.specmatic.reporter.commands.InsightsReportOptions
import java.io.File

fun sendRawReportToInsights(
    report: File?,
    reportType: RawReportType,
    options: InsightsReportOptions,
    git: SystemGit = SystemGit(),
) {
    if (report == null) return
    runCatching {
        val repoUrl = options.repoUrl ?: git.getRemoteUrl()
        RawReportSender.send(
            reportFile = report,
            reportType = reportType,
            ci = options.ci,
            buildId = options.buildId,
            repoId = options.repoId,
            repoName = options.repoName ?: repoUrl.substringAfterLast('/').removeSuffix(".git"),
            repoUrl = repoUrl,
            branchName = options.branchName ?: git.currentBranch(),
            token = options.token,
        )
    }.onFailure { logger.debug("Could not send the ${reportType.wireValue} report to Insights: ${it.message}") }
}
