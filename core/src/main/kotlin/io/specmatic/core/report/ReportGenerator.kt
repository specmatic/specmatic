package io.specmatic.core.report

import io.specmatic.core.getConfigFilePath
import io.specmatic.core.log.consoleLog
import io.specmatic.reporter.commands.VersionProvider
import io.specmatic.reporter.ctrf.CtrfReportGenerator
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.reporting.ReportProvider
import io.specmatic.specmatic.core.VersionInfo
import java.io.File
import java.util.*

object ReportGenerator {
    fun generateReport(
        testResultRecords: List<CtrfTestResultRecord>,
        startTime: Long,
        endTime: Long,
        specConfigs: List<CtrfSpecConfig>,
        coverage: Int? = null,
        reportDir: File,
        toolName: String = "Specmatic v${VersionInfo.describe()}",
        getCoverageStatus: (List<CtrfTestResultRecord>) -> CoverageStatus
    ) {
        val extra = buildMap<String, Any> {
            coverage?.let { put("apiCoverage", "$coverage%") }
            put("specmaticConfigPath", getConfigFilePath())
        }

        consoleLog("Generating report for ${testResultRecords.size} tests...")

        val report = CtrfReportGenerator.generate(
            testResultRecords = testResultRecords,
            startTime = startTime,
            endTime = endTime,
            extra = extra,
            specConfig = specConfigs,
            getCoverageStatus = getCoverageStatus,
            toolName = toolName
        )

        ServiceLoader.load(ReportProvider::class.java).forEach { hook ->
            hook.generateReport(report, reportDir)
        }
    }
}
