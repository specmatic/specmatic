package io.specmatic.conversions.lenient

import io.specmatic.core.Result

interface DiagnosticCollector {
    fun record(entry: Result.Failure)

    fun toResult(): Result

    fun getEntries(): List<Result.Failure>
}

class DiagnosticCollectorImpl: DiagnosticCollector {
    private val entries = mutableListOf<Result.Failure>()

    override fun record(entry: Result.Failure) {
        entries.add(entry)
    }

    override fun toResult(): Result {
        return if (entries.isEmpty())
            Result.Success()
        else
            Result.fromFailures(entries.distinctBy { it.reportString() })
    }

    override fun getEntries(): List<Result.Failure> = entries
}
