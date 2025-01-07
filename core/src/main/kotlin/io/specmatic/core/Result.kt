package io.specmatic.core

import io.specmatic.core.Result.Failure
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.value.Value

sealed class Result {
    var scenario: ScenarioDetailsForResult? = null
    var contractPath: String? = null

    companion object {
        fun fromFailures(failures: List<Failure>): Result {
            return if(failures.isNotEmpty())
                Failure.fromFailures(failures)
            else
                Success()
        }

        fun fromResults(results: List<Result>): Result {
            val failures = results.filterIsInstance<Failure>()
            return fromFailures(failures)
        }
    }

    fun reportString(): String {
        return toReport().toText()
    }

    fun isFluffy(): Boolean {
        return isFluffy(0)
    }

    fun isFluffy(acceptableFluffLevel: Int): Boolean {
        return when(this) {
            is Failure ->
                failureReason?.let { it.fluffLevel > acceptableFluffLevel } == true || cause?.isFluffy(acceptableFluffLevel) == true
            else -> false
        }
    }

    abstract fun isAnyFluffy(acceptableFluffLevel: Int): Boolean

    fun updateScenario(scenario: ScenarioDetailsForResult): Result {
        this.scenario = scenario
        return this
    }

    abstract fun isSuccess(): Boolean

    abstract fun ifSuccess(function: () -> Result): Result
    abstract fun withBindings(bindings: Map<String, String>, response: HttpResponse): Result
    abstract fun breadCrumb(breadCrumb: String): Result
    abstract fun failureReason(failureReason: FailureReason?): Result

    abstract fun shouldBeIgnored(): Boolean

    fun updatePath(path: String): Result {
        this.contractPath = path
        return this
    }

    fun toReport(scenarioMessage: String? = null): Report {
        return when (this) {
            is Failure -> toFailureReport(scenarioMessage)
            else -> SuccessReport
        }
    }

    abstract fun partialSuccess(message: String): Result
    abstract fun isPartialSuccess(): Boolean

    abstract fun isPartialFailure(): Boolean

    abstract fun testResult(): TestResult
    abstract fun withFailureReason(urlPathMisMatch: FailureReason): Result
    abstract fun throwOnFailure(): Success
    abstract fun <T> toReturnValue(returnValue: T, errorMessage: String): ReturnValue<T>
    abstract fun <V> onSuccessElseNull(function: () -> V): V?

    data class FailureCause(val message: String="", var cause: Failure? = null) {
        fun hasReason(failureReason: FailureReason): Boolean {
            return cause?.hasReason(failureReason) ?: false
        }

        fun filterByReason(failureReason: FailureReason): FailureCause? {
            val cause = cause ?: return null

            if(cause.failureReason == failureReason)
                return this

            val filteredCause = cause.filterByReason(failureReason)

            if(filteredCause.isEmpty())
                return null

            return this.copy(cause = filteredCause)
        }

        fun hasAnyOfTheseReasons(failureReasons: List<FailureReason>): Boolean {
            return cause?.hasAnyOfTheseReasons(*failureReasons.toTypedArray()) ?: false
        }

        fun removeReasonsFromCauses(): FailureCause {
            return this.copy(cause = cause?._removeReasonsFromCauses())
        }

        fun reasonIs(reasonFilter: (failureReason: FailureReason) -> Boolean): Boolean {
            return (cause ?: return false).reasonIs(reasonFilter)
        }

        fun failureCount(): Int {
            return cause?.failureCount() ?: 1
        }
    }

    data class Failure(val causes: List<FailureCause> = emptyList(), val breadCrumb: String = "", val failureReason: FailureReason? = null, val isPartial: Boolean = false) : Result() {
        constructor(message: String="", cause: Failure? = null, breadCrumb: String = "", failureReason: FailureReason? = null, isPartial: Boolean? = false): this(listOf(FailureCause(message, cause)), breadCrumb, failureReason, isPartial ?: false)

        companion object {
            fun fromFailures(failures: List<Failure>): Failure {
                return Failure(failures.map {
                    it.toFailureCause()
                }, isPartial = failures.all { it.isPartial })
            }
        }

        val message = causes.firstOrNull()?.message ?: ""
        val cause = causes.firstOrNull()?.cause

        fun toFailureCause(): FailureCause {
            return FailureCause(cause = this)
        }

        fun getFailureBreadCrumbs(prefix: String): List<String> {
            return causes.mapNotNull { it.cause?.getFailureBreadCrumbs("$prefix$breadCrumb.") }
                .flatten()
                .plus("$prefix$breadCrumb")
        }


        override fun ifSuccess(function: () -> Result) = this
        override fun withBindings(bindings: Map<String, String>, response: HttpResponse): Result {
            return this
        }

        override fun shouldBeIgnored(): Boolean {
            return this.scenario?.ignoreFailure == true
        }

        override fun partialSuccess(message: String): Result {
            return this
        }

        override fun isPartialSuccess(): Boolean = false
        override fun isPartialFailure(): Boolean = isPartial
        override fun testResult(): TestResult {
            if(shouldBeIgnored())
                return TestResult.Error

            return TestResult.Failed
        }

        override fun withFailureReason(failureReason: FailureReason): Result {
            return copy(failureReason = failureReason)
        }

        override fun throwOnFailure(): Success {
            throw ContractException(this.toFailureReport())
        }

        override fun <T> toReturnValue(returnValue: T, errorMessage: String): ReturnValue<T> {
            return HasFailure(this)
        }

        override fun <V> onSuccessElseNull(function: () -> V): V? {
            return null
        }

        fun reason(errorMessage: String) = Failure(errorMessage, this)
        override fun breadCrumb(breadCrumb: String) = Failure(cause = this, breadCrumb = breadCrumb, isPartial = isPartial)
        override fun failureReason(failureReason: FailureReason?): Result {
            return this.copy(failureReason = failureReason)
        }

        fun toFailureReport(scenarioMessage: String? = null): FailureReport {
            return FailureReport(contractPath, scenarioMessage, scenario, toMatchFailureDetailList())
        }

        fun toMatchFailureDetails(): MatchFailureDetails {
            return (cause?.toMatchFailureDetails() ?: MatchFailureDetails()).let { reason ->
                when {
                    message.isNotEmpty() -> reason.copy(errorMessages = listOf(message).plus(reason.errorMessages), isPartial = isPartial)
                    else -> reason
                }
            }.let { reason ->
                when {
                    breadCrumb.isNotEmpty() -> reason.copy(breadCrumbs = listOf(breadCrumb).plus(reason.breadCrumbs))
                    else -> reason
                }
            }
        }

        fun toMatchFailureDetailList(): List<MatchFailureDetails> {
            return causes.flatMap {
                (it.cause?.toMatchFailureDetailList() ?: listOf(MatchFailureDetails())).map { matchFailureDetails ->
                    val withReason = when {
                        message.isNotEmpty() -> matchFailureDetails.copy(errorMessages = listOf(message).plus(matchFailureDetails.errorMessages), isPartial = isPartial)
                        else -> matchFailureDetails
                    }

                    when {
                        breadCrumb.isNotEmpty() -> withReason.copy(breadCrumbs = listOf(breadCrumb).plus(withReason.breadCrumbs))
                        else -> withReason
                    }
                }
            }
        }

        override fun isAnyFluffy(acceptableFluffLevel: Int): Boolean {
            return failureReason?.let { it.fluffLevel > acceptableFluffLevel } == true || causes.any { it.cause?.isAnyFluffy(acceptableFluffLevel) == true }
        }

        override fun isSuccess() = false

        fun traverseFailureReason(): FailureReason? {
            return failureReason ?: causes.asSequence().map {
                it.cause?.traverseFailureReason()
            }.firstOrNull()
        }

        fun hasReason(failureReason: FailureReason): Boolean {
            return this.failureReason == failureReason || causes.any { it.hasReason(failureReason) }
        }

        fun hasAnyOfTheseReasons(vararg failureReasons: FailureReason): Boolean {
            return this.failureReason != null && this.failureReason in failureReasons || causes.any { it.hasAnyOfTheseReasons(failureReasons.toList()) }
        }

        fun filterByReason(failureReason: FailureReason): Failure {
            if(this.failureReason == FailureReason.DiscriminatorMismatch)
                return this

            val causesFilteredByReason: List<FailureCause> = this.causes.mapNotNull {
                it.filterByReason(failureReason)
            }

            return this.copy(causes = causesFilteredByReason)
        }

        fun isEmpty(): Boolean {
            return this.causes.isEmpty()
        }

        fun removeReasonsFromCauses(): Failure {
            return this.copy(causes = causes.map { it.removeReasonsFromCauses() })
        }

        fun _removeReasonsFromCauses(): Failure {
            return this.copy(causes = causes.map { it.removeReasonsFromCauses() }, failureReason = null)
        }

        fun reasonIs(reasonFilter: (failureReason: FailureReason) -> Boolean): Boolean {
            return (failureReason?.let { reasonFilter(it) } ?: false) || causes.any { it.reasonIs(reasonFilter) }
        }

        fun failureCount(): Int {
            return causes.sumOf { it.failureCount() }
        }
    }

    data class Success(val variables: Map<String, String> = emptyMap(), val partialSuccessMessage: String? = null) : Result() {
        override fun isAnyFluffy(acceptableFluffLevel: Int): Boolean {
            return false
        }

        override fun isSuccess() = true
        override fun ifSuccess(function: () -> Result) = function()
        override fun withBindings(bindings: Map<String, String>, response: HttpResponse): Result {
            return this.copy(variables = response.export(bindings))
        }

        override fun breadCrumb(breadCrumb: String): Result {
            return this
        }

        override fun failureReason(failureReason: FailureReason?): Result {
            return this
        }

        override fun shouldBeIgnored(): Boolean = false
        override fun partialSuccess(message: String): Result {
            return this.copy(partialSuccessMessage = message)
        }

        override fun isPartialSuccess(): Boolean = partialSuccessMessage != null
        override fun isPartialFailure(): Boolean = false
        override fun testResult(): TestResult {
            return TestResult.Success
        }

        override fun withFailureReason(urlPathMisMatch: FailureReason): Result {
            return this
        }

        override fun throwOnFailure(): Success {
            return this
        }

        override fun <T> toReturnValue(returnValue: T, errorMessage: String): ReturnValue<T> {
            return HasValue(returnValue)
        }

        override fun <V> onSuccessElseNull(function: () -> V): V? {
            return function()
        }
    }
}

enum class TestResult {
    Success,
    Error,
    Failed,
    NotImplemented,
    MissingInSpec,
    NotCovered
}

enum class FailureReason(val fluffLevel: Int, val objectMatchOccurred: Boolean) {
    PartNameMisMatch(0, false),
    StatusMismatch(2, false),
    IdentifierMismatch(1, false),
    MethodMismatch(2, false),
    ContentTypeMismatch(1, false),
    RequestMismatchButStatusAlsoWrong(2, false),
    URLPathMisMatch(2, false),
    SOAPActionMismatch(2, false),
    DiscriminatorMismatch(0, true),
    FailedButDiscriminatorMatched(0, true),
    FailedButObjectTypeMatched(0, true),
    ScenarioMismatch(2, false)
}

data class MatchFailureDetails(val breadCrumbs: List<String> = emptyList(), val errorMessages: List<String> = emptyList(), val path: String? = null, val isPartial: Boolean = false)

interface MismatchMessages {
    fun mismatchMessage(expected: String, actual: String): String
    fun unexpectedKey(keyLabel: String, keyName: String): String
    fun expectedKeyWasMissing(keyLabel: String, keyName: String): String
    fun optionalKeyMissing(keyLabel: String, keyName: String): String {
        return expectedKeyWasMissing("optional ${keyLabel.lowercase()}", keyName)
    }
    fun valueMismatchFailure(expected: String, actual: Value?, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure {
        return mismatchResult(expected, valueError(actual) ?: "null", mismatchMessages)
    }
}

object DefaultMismatchMessages: MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Expected $expected, actual was $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.lowercase().capitalizeFirstChar()} named \"$keyName\" was unexpected"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "Expected ${keyLabel.lowercase()} named \"$keyName\" was missing"
    }

    override fun optionalKeyMissing(keyLabel: String, keyName: String): String {
        return "Expected optional ${keyLabel.lowercase()} named \"$keyName\" was missing"
    }
}

fun mismatchResult(expected: String, actual: String, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure = Failure(mismatchMessages.mismatchMessage(expected, actual))
fun mismatchResult(expected: String, actual: Value?, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure = mismatchMessages.valueMismatchFailure(expected, actual, mismatchMessages)
fun mismatchResult(expected: Value, actual: Value?, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure = mismatchResult(valueError(expected) ?: "null", valueError(actual) ?: "nothing", mismatchMessages)
fun mismatchResult(expected: Pattern, actual: String, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure = mismatchResult(expected.typeName, actual, mismatchMessages)
fun mismatchResult(pattern: Pattern, sampleData: Value?, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure = mismatchResult(pattern, sampleData?.toStringLiteral() ?: "null", mismatchMessages)
fun mismatchResult(thisPattern: Pattern, otherPattern: Pattern, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure {
    return mismatchResult(thisPattern.typeName, otherPattern.typeName, mismatchMessages)
}

fun valueError(value: Value?): String? {
    return value?.valueErrorSnippet()
}
