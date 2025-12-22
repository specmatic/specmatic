package io.specmatic.core.report

import io.specmatic.core.getConfigFilePath
import io.specmatic.core.log.consoleLog
import io.specmatic.reporter.ctrf.CtrfReportGenerator
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.reporting.ReportProvider
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
            getCoverageStatus = getCoverageStatus
        )

        ServiceLoader.load(ReportProvider::class.java).forEach { hook ->
            hook.generateReport(report, reportDir)
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
