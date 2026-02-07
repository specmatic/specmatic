package io.specmatic.core.config.v3.specmatic

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.nio.file.Path

enum class ReportFormat(val value: String) {
    HTML("html"),
    CTRF("ctrf");

    @JsonValue
    fun toValue(): String = value

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String): ReportFormat {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: throw IllegalArgumentException(
                "Invalid Report format $value, must be either ${entries.joinToString(separator = " or ", transform = { it.value })}"
            )
        }
    }
}

data class Report(val formats: List<ReportFormat>? = null, val outputDirectory: Path? = null)
