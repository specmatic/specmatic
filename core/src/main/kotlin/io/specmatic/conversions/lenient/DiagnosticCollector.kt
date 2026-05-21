package io.specmatic.conversions.lenient

import io.specmatic.core.Result

open class DiagnosticCollector {
    private val entries = mutableListOf<Result.Failure>()

    open fun record(entry: Result.Failure) {
        entries.add(entry)
    }

    open fun toResult(): Result {
        return if (entries.isEmpty())
            Result.Success()
        else
            Result.fromFailures(entries.distinctBy { it.reportString() })
    }

    open fun addEntries(entries: List<Result.Failure>) { this.entries.addAll(entries) }

    open fun getEntries(): List<Result.Failure> = entries
}

object NoOpDiagnosticCollector : DiagnosticCollector() {
    override fun record(entry: Result.Failure) = Unit
    override fun toResult(): Result = Result.Success()
    override fun addEntries(entries: List<Result.Failure>) = Unit
    override fun getEntries(): List<Result.Failure> = emptyList()
}
