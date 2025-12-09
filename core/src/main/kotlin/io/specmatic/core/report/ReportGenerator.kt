package io.specmatic.core.report

import io.specmatic.core.getConfigFilePath
import io.specmatic.reporter.ctrf.CtrfReportGenerator
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.reporting.ReportProvider
import io.specmatic.test.TestResultRecord
import io.specmatic.test.TestResultRecord.Companion.getCoverageStatus
import java.io.File
import java.util.*

object ReportGenerator {
    fun generateReport(
        testResultRecords: List<TestResultRecord>,
        startTime: Long,
        endTime: Long,
        specConfigs: List<CtrfSpecConfig>,
        coverage: Int? = null,
        reportDir: File,
    ) {
        val extra = buildMap<String, Any> {
            coverage?.let { put("apiCoverage", "$coverage%") }
            put("specmaticConfigPath", getConfigFilePath())
        }

        val report = CtrfReportGenerator.generate(
            testResultRecords = testResultRecords,
            startTime = startTime,
            endTime = endTime,
            extra = extra,
            specConfig = specConfigs,
            getCoverageStatus = { ctrfTestResultRecords ->
                ctrfTestResultRecords.filterIsInstance<TestResultRecord>().getCoverageStatus()
            },
        )

        ServiceLoader.load(ReportProvider::class.java).forEach { hook ->
            hook.generateReport(report, reportDir)
        }
    }

}
