package io.specmatic.conversions.lenient

import io.specmatic.core.Result

class DiagnosticCollector {
    private val entries = mutableListOf<Result.Failure>()

    fun record(entry: Result.Failure) {
        entries.add(entry)
    }

    fun toResult(): Result {
        return if (entries.isEmpty())
            Result.Success()
        else
            Result.fromFailures(entries.distinctBy { it.reportString() })
    }

    fun getEntries(): List<Result.Failure> = entries
}
