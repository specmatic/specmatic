package io.specmatic.reports

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StubUsageReport(
    val specmaticConfigPath: String?,
    val stubUsage: List<ReportItem>,
) {
    fun toJson(): String = json.encodeToString(this)

    fun merge(other: StubUsageReport): StubUsageReport {
        return StubUsageReport(
            specmaticConfigPath = specmaticConfigPath ?: other.specmaticConfigPath,
            stubUsage = this.stubUsage.plus(other.stubUsage).groupBy { it.hashCodeByIdentity() }.values.map { items ->
                items.reduce(ReportItem::merge)
            },
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true; }

        fun readFromJson(file: File): StubUsageReport = json.decodeFromString(file.readText())
    }
}
