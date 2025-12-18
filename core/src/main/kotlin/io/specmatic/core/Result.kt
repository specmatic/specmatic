package io.specmatic.core

import io.specmatic.core.Result.Failure
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.value.Value
import io.specmatic.reporter.model.TestResult

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

    fun updateScenario(scenario: ScenarioDetailsForResult?): Result {
        this.scenario = scenario
        return this
    }

    abstract fun isSuccess(): Boolean

    abstract fun ifSuccess(function: () -> Result): Result
    abstract fun withBindings(bindings: Map<String, String>, response: HttpResponse): Result
    abstract fun breadCrumb(breadCrumb: String): Result
    abstract fun failureReason(failureReason: FailureReason?): Result
    open fun withRuleViolation(ruleViolation: RuleViolation): Result = this

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
    abstract fun <T> toReturnValue(returnValue: T, errorMessage: String? = null): ReturnValue<T>
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

    data class Failure(val causes: List<FailureCause> = emptyList(), val breadCrumb: String = "", val failureReason: FailureReason? = null, val isPartial: Boolean = false, val ruleViolationReport: RuleViolationReport? = null) : Result() {
        constructor(
            message: String = "",
            cause: Failure? = null,
            breadCrumb: String = "",
            failureReason: FailureReason? = null,
            isPartial: Boolean? = false,
            ruleViolation: RuleViolation? = null
        ) : this (
            causes = listOf(element = FailureCause(message, cause)),
            breadCrumb = breadCrumb,
            failureReason = failureReason,
            isPartial = isPartial ?: false,
            ruleViolationReport = ruleViolation?.let(RuleViolationReport::from)
        )

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

        fun withResponseRelatedCauses(): Failure {
            val responseRelatedCause = if (this.cause?.breadCrumb?.contains("RESPONSE") == true) {
                this.cause
            } else null

            return this.copy(
                causes = listOfNotNull(responseRelatedCause?.toFailureCause()) + this.causes.mapNotNull {
                    it.cause?.withResponseRelatedCauses()?.toFailureCause()
                }
            )
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

        override fun withFailureReason(failureReason: FailureReason): Failure {
            return copy(failureReason = failureReason)
        }

        override fun throwOnFailure(): Success {
            throw ContractException(this.toFailureReport())
        }

        override fun <T> toReturnValue(returnValue: T, errorMessage: String?): ReturnValue<T> {
            return HasFailure(this, errorMessage.orEmpty())
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

                    val withBreadCrumbs = when {
                        breadCrumb.isNotEmpty() -> withReason.copy(breadCrumbs = listOf(breadCrumb).plus(withReason.breadCrumbs))
                        else -> withReason
                    }

                    when {
                        ruleViolationReport != null -> withBreadCrumbs.copy(ruleViolationReport = ruleViolationReport.plus(withBreadCrumbs.ruleViolationReport))
                        else -> withBreadCrumbs
                    }
                }
            }
        }

        override fun isAnyFluffy(acceptableFluffLevel: Int): Boolean {
            return failureReason?.let { it.fluffLevel > acceptableFluffLevel } == true || causes.any { it.cause?.isAnyFluffy(acceptableFluffLevel) == true }
        }

        override fun isSuccess() = false

        override fun withRuleViolation(ruleViolation: RuleViolation): Failure {
            val ruleViolationReport = this.ruleViolationReport ?: RuleViolationReport()
            return copy(ruleViolationReport = ruleViolationReport.withViolation(ruleViolation))
        }

        fun withRuleViolationReport(ruleViolationReport: RuleViolationReport? = null): Failure {
            if (this.ruleViolationReport == null) return copy(ruleViolationReport = ruleViolationReport)
            return copy(ruleViolationReport = this.ruleViolationReport.plus(ruleViolationReport))
        }

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

        override fun <T> toReturnValue(returnValue: T, errorMessage: String?): ReturnValue<T> {
            return HasValue(returnValue)
        }

        override fun <V> onSuccessElseNull(function: () -> V): V? {
            return function()
        }
    }
}

enum class FailureReason(val fluffLevel: Int, val objectMatchOccurred: Boolean) {
    PartNameMisMatch(0, false),
    StatusMismatch(2, false),
    IdentifierMismatch(1, false),
    MethodMismatch(2, false),
    ContentTypeMismatch(1, false),
    RequestMismatchButStatusAlsoWrong(2, false),
    URLPathMisMatch(2, false),
    URLPathParamMismatchButSameStructure(1, false),
    URLPathParamMatchButConflict(2, false),
    SOAPActionMismatch(2, false),
    DiscriminatorMismatch(0, true),
    FailedButDiscriminatorMatched(0, true),
    FailedButObjectTypeMatched(0, true),
    ScenarioMismatch(2, false)
}

data class MatchFailureDetails(
    val breadCrumbs: List<String> = emptyList(),
    val errorMessages: List<String> = emptyList(),
    val path: String? = null,
    val isPartial: Boolean = false,
    val ruleViolationReport: RuleViolationReport? = null
)

interface MismatchMessages {
    fun mismatchMessage(expected: String, actual: String): String
    fun unexpectedKey(keyLabel: String, keyName: String): String
    fun expectedKeyWasMissing(keyLabel: String, keyName: String): String
    fun optionalKeyMissing(keyLabel: String, keyName: String): String {
        return expectedKeyWasMissing("optional ${keyLabel.lowercase()}", keyName)
    }

    fun valueMismatchFailure(expected: String, actual: Value?, mismatchMessages: MismatchMessages = this): Failure {
        return valueMismatchResult(expected, valueError(actual) ?: "null", mismatchMessages)
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

fun mismatchFailure(
    expected: String,
    actual: String,
    mismatchMessages: MismatchMessages,
    ruleViolation: RuleViolation,
): Failure = Failure(message = mismatchMessages.mismatchMessage(expected, actual), ruleViolation = ruleViolation)

fun valueError(value: Value?): String? = value?.valueErrorSnippet()

// Value Mismatch Result
fun valueMismatchResult(
    expected: String,
    actual: String,
    mismatchMessages: MismatchMessages = DefaultMismatchMessages
): Failure = mismatchFailure(expected, actual, mismatchMessages, ruleViolation = StandardRuleViolation.VALUE_MISMATCH)

fun valueMismatchResult(
    expected: String,
    actual: Value?,
    mismatchMessages: MismatchMessages = DefaultMismatchMessages
): Failure = mismatchMessages.valueMismatchFailure(expected, actual, mismatchMessages)

fun valueMismatchResult(
    expected: Value,
    actual: Value?,
    mismatchMessages: MismatchMessages = DefaultMismatchMessages
): Failure = mismatchMessages.valueMismatchFailure(valueError(expected) ?: "null", actual, mismatchMessages)

// Data Type Mismatch Result
fun dataTypeMismatchResult(
    expected: Pattern,
    actual: String,
    mismatchMessages: MismatchMessages = DefaultMismatchMessages
): Failure = mismatchFailure(expected.typeName, actual, mismatchMessages, StandardRuleViolation.TYPE_MISMATCH)

fun dataTypeMismatchResult(
    pattern: Pattern,
    sampleData: Value?,
    mismatchMessages: MismatchMessages = DefaultMismatchMessages
): Failure = dataTypeMismatchResult(pattern, valueError(sampleData) ?: "null", mismatchMessages)

fun dataTypeMismatchResult(
    expected: String,
    sampleData: Value?,
    mismatchMessages: MismatchMessages = DefaultMismatchMessages
): Failure = mismatchFailure(expected, valueError(sampleData) ?: "null", mismatchMessages, StandardRuleViolation.TYPE_MISMATCH)

// Constraint Mismatch
fun constraintMismatchResult(
    expected: String,
    actual: String,
    mismatchMessages: MismatchMessages = DefaultMismatchMessages
): Failure = Failure(message = mismatchMessages.mismatchMessage(expected, actual), ruleViolation = StandardRuleViolation.CONSTRAINT_VIOLATION)

fun constraintMismatchResult(
    expected: String,
    actual: Value?,
    mismatchMessages: MismatchMessages = DefaultMismatchMessages
): Failure = constraintMismatchResult(expected, valueError(actual) ?: "null", mismatchMessages)

// Pattern Mismatch Result
fun patternMismatchResult(
    thisPattern: Pattern,
    otherPattern: Pattern,
    mismatchMessages: MismatchMessages = DefaultMismatchMessages
): Failure = Failure(message = mismatchMessages.mismatchMessage(thisPattern.typeName, otherPattern.typeName))

