package io.specmatic.core.report

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.getVersion
import io.specmatic.core.config.resolveTemplates
import io.specmatic.core.config.v2.SpecmaticConfigV2
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
        ReportProvider.generateHtmlReport(report, reportDir, specmaticConfigV2AsMap())
    }

    // Currently HTML report generator is able to ingest specmatic config v2.
    // Once the HTML report generator becomes independent of the specmatic config variants,
    // this will simply read the text from the file and convert whatever is present into a map and return
    fun specmaticConfigV2AsMap(): Map<String, Any> {
        val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        val configTree = resolveTemplates(objectMapper.readTree(File(getConfigFilePath())))
        return when (configTree.getVersion()) {
            SpecmaticConfigVersion.VERSION_2 -> {
                runCatching {
                    val specmaticConfigV2 = objectMapper.treeToValue(configTree, SpecmaticConfigV2::class.java)
                    objectMapper.convertValue(
                        specmaticConfigV2,
                        object : TypeReference<Map<String, Any>>() {}
                    )
                }.getOrElse { emptyMap() }
            }

            else -> emptyMap()
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
