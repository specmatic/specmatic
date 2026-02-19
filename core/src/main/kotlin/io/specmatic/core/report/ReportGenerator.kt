package io.specmatic.core.report

import io.specmatic.core.config.toResolvedSpecmaticConfigMap
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.log.consoleLog
import io.specmatic.reporter.ctrf.CtrfReportGenerator
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.reporting.ReportProvider
import io.specmatic.specmatic.core.VersionInfo
import java.io.File

object ReportGenerator {
    fun generateReport(
        testResultRecords: List<CtrfTestResultRecord>,
        startTime: Long,
        endTime: Long,
        specConfigs: List<CtrfSpecConfig>,
        coverage: Int? = null,
        reportDir: File,
        toolName: String = "Specmatic ${VersionInfo.describe()}",
        getCoverageStatus: (List<CtrfTestResultRecord>) -> CoverageStatus
    ) {
        if(isCtrfSpecConfigsValid(specConfigs).not()) return

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

        ReportProvider.generateCtrfReport(report, reportDir)
        ReportProvider.generateHtmlReport(report, reportDir, specmaticConfigAsMap())
    }

    internal fun specmaticConfigAsMap(): Map<String, Any> {
        return try {
            File(getConfigFilePath()).toResolvedSpecmaticConfigMap()
        } catch(_: Throwable) {
            emptyMap()
        }
    }

    private fun isCtrfSpecConfigsValid(specConfigs: List<CtrfSpecConfig>): Boolean {
        if (specConfigs.isEmpty()) {
            consoleLog("Skipping the CTRF report generation as ctrf spec configs list is empty.")
            return false
        }
        if (specConfigs.any { it.specification.isBlank() }) {
            consoleLog("Skipping the CTRF report generation as ctrf spec configs list contains an entry with blank specification.")
            return false
        }
        return true
    }
}
