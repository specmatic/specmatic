package io.specmatic.core

import io.specmatic.core.report.BccReportGenerator
import io.specmatic.core.report.ReportGenerator
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.Value
import io.specmatic.reporter.ctrf.model.CtrfBackwardCompatibilityRecord
import io.specmatic.reporter.ctrf.model.CtrfReport

private const val BCC_REPORT_DIR_SUFFIX = "backward_compatibility"
private const val SPECMATIC_BCC_REPORT_FLAG = "SPECMATIC_BCC_REPORT"

fun testBackwardCompatibility(older: Feature, newer: Feature): Results {
    return testBackwardCompatibilityWithReport(older, newer).first
}

internal fun testBackwardCompatibilityWithReport(older: Feature, newer: Feature): Pair<Results, CtrfReport?> {
    val startTime = System.currentTimeMillis()
    val records = OpenApiBackwardCompatibilityChecker(older, newer).run()
    val endTime = System.currentTimeMillis()

    val result = records.toBackwardCompatibilityStatuses()
    val report = generateBackwardCompatibilityReport(records, startTime, endTime)
    return Pair(result, report)
}

fun backwardCompatibilityRecords(older: Feature, newer: Feature): Pair<Results, List<CtrfBackwardCompatibilityRecord>> {
    val records = OpenApiBackwardCompatibilityChecker(older, newer).run()
    return records.toBackwardCompatibilityStatuses() to records
}

fun List<CtrfBackwardCompatibilityRecord>.toBackwardCompatibilityStatuses(): Results {
    return filterIsInstance<OpenApiBackwardCompatibilityCheckRecord>()
        .groupBy { it.operations }.values
        .fold(Results()) { acc, recordsForOperation ->
            acc.plus(Results(recordsForOperation.map { it.compatResult }).distinct())
        }
}

fun generateBackwardCompatibilityReport(records: List<CtrfBackwardCompatibilityRecord>, startTime: Long, endTime: Long): CtrfReport? {
    if (records.isEmpty()) return null
    if (!Flags.getBooleanValue(SPECMATIC_BCC_REPORT_FLAG)) return null
    val reportOperations = BccReportGenerator().generateReportOperations(records)
    val reportDir = loadSpecmaticConfigOrDefault(getConfigFileName()).getReportDirPath(BCC_REPORT_DIR_SUFFIX).toFile()
    return ReportGenerator.generateReportBcc(
        endTime = endTime,
        startTime = startTime,
        reportDir = reportDir,
        coverageReportOperations = reportOperations,
        specConfigs = reportOperations.map { it.specConfig }.distinct(),
    )
}

object NewAndOldSpecificationRequestMismatches: MismatchMessages {
    override fun toPartFromValue(value: Value?): String {
        return when (value) {
            is NullValue -> "nullable"
            else -> value?.type()?.typeName ?: "null"
        }
    }

    override fun mismatchMessage(expected: String, actual: String): String {
        return "This is $expected in the new specification, but $actual in the old specification"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" in the request from the old specification is not in the new specification"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "New specification expects $keyLabel \"$keyName\" in the request but it is missing from the old specification"
    }

    override fun typeMismatch(expectedType: String, actualValue: String?, actualType: String?): String {
        val expectedPart = "type $expectedType"
        val actualPart = "type $actualType"
        return mismatchMessage(expectedPart, actualPart)
    }
}

object NewAndOldSpecificationResponseMismatches: MismatchMessages {
    override fun toPartFromValue(value: Value?): String {
        return when (value) {
            is NullValue -> "nullable"
            else -> value?.type()?.typeName ?: "null"
        }
    }

    override fun mismatchMessage(expected: String, actual: String): String {
        return "This is $actual in the new specification response but $expected in the old specification"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" in the response from the new specification is not in the old specification"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "The old specification expects $keyLabel \"$keyName\" but it is missing in the new specification"
    }

    override fun typeMismatch(expectedType: String, actualValue: String?, actualType: String?): String {
        val expectedPart = "type $expectedType"
        val actualPart = "type $actualType"
        return NewAndOldSpecificationRequestMismatches.mismatchMessage(expectedPart, actualPart)
    }
}
