package io.specmatic.reports

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TestCoverageReport(
    val specmaticConfigPath: String?,
    val apiCoverage: List<ReportItem>,
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}
