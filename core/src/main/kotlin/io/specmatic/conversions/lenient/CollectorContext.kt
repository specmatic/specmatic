package io.specmatic.conversions.lenient

import io.specmatic.core.BreadCrumb
import io.specmatic.core.Result
import io.specmatic.core.RuleViolation
import io.specmatic.core.jsonoperator.PathSegment
import io.specmatic.core.value.toBigDecimal
import io.specmatic.test.asserts.toFailure

const val DEFAULT_ARRAY_INDEX = -1
data class CollectorContext(private val collector: DiagnosticCollector = DiagnosticCollectorImpl(), private val pathSegments: List<PathSegment> = emptyList()) {
    val path: String? = pathSegments.joinToString(".", transform = PathSegment::internalPointerRepresentation).takeUnless(String::isBlank)
    val hasPath: Boolean = path != null
    val pathOrRoot: String = path ?: "/"

    fun at(name: String): CollectorContext {
        val newFullPath = BreadCrumb.combine(pathOrRoot, name)
        return CollectorContext(collector, pathSegments + PathSegment.Key(name, newFullPath))
    }

    fun at(index: Int): CollectorContext {
        val newFullPath = BreadCrumb.combine(pathOrRoot, index.toString())
        return CollectorContext(collector, pathSegments + PathSegment.Index(index, newFullPath))
    }

    fun withPath(path: String): CollectorContext {
        val newSegments = PathSegment.parsePath(path).withDefault(emptyList()) { it }
        return CollectorContext(collector, newSegments)
    }

    fun <T> safely(fallback: () -> T, message: String? = null, ruleViolation: RuleViolation? = null, block: (CollectorContext) -> T): T {
        return runCatching { block(this) }.getOrElse { e ->
            recordException(message, e, ruleViolation)
            fallback()
        }
    }

    fun <T> check(value: T, isValid: CollectorContext.(T) -> Boolean): DiagnosticMessage<T> {
        return DiagnosticMessageBuilder(this, value = value, isValid = isValid(value))
    }

    fun <T> checkOptional(value: T?, isValid: CollectorContext.(T?) -> Boolean): DiagnosticMessage<T> {
        return DiagnosticMessageBuilder(this, value = value, isValid = isValid(value))
    }

    fun <T> check(name: String, value: T, isValid: CollectorContext.(T) -> Boolean): DiagnosticMessage<T> {
        return DiagnosticMessageBuilder(this, name, value, isValid(value))
    }

    fun <T> checkOptional(name: String, value: T?, isValid: CollectorContext.(T?) -> Boolean): DiagnosticMessage<T> {
        return DiagnosticMessageBuilder(this, name, value, isValid(value))
    }

    fun attempt(message: String? = null, ruleViolation: RuleViolation? = null, block: (CollectorContext) -> Unit): Boolean {
        return runCatching { block(this); true }.getOrElse { e ->
            recordException(message, e, ruleViolation)
            false
        }
    }

    fun record(message: String, isWarning: Boolean = false, ruleViolation: RuleViolation? = null) {
        val failure = Result.Failure(message = message, isPartial = isWarning, ruleViolation = ruleViolation)
        recordError(failure)
    }

    fun buildPath(name: String): String {
        return (pathSegments + PathSegment.Key(name, pathOrRoot)).joinToString(
            separator = ".",
            transform = PathSegment::internalPointerRepresentation
        )
    }

    fun recordEntry(entry: Result.Failure) = collector.record(entry)

    fun toCollector(): DiagnosticCollector = collector

    private fun recordException(message: String?, exception: Throwable, ruleViolation: RuleViolation? = null) {
        val exceptionFailure = exception.toFailure()
        recordError(if (message != null) {
            Result.Failure(message = message, cause = exceptionFailure, ruleViolation = ruleViolation)
        } else {
            ruleViolation?.let { exceptionFailure.withRuleViolation(it) } ?: exceptionFailure
        })
    }

    private fun recordError(failure: Result.Failure) = recordEntry(failure.breadCrumb(pathOrRoot))
}

fun <T: Number> CollectorContext.requireMinimum(name: String, value: T, minimum: T, ruleViolation: RuleViolation, message: ((T, T) -> String)? = null): T {
    val valueInBigDecimal = value.toBigDecimal()
    val minimumInBigDecimal = minimum.toBigDecimal()
    return check(name = name, value = value, isValid = { valueInBigDecimal >= minimumInBigDecimal })
        .violation { ruleViolation }
        .message { message?.invoke(value, minimum) ?: "$name $value cannot be less than $minimum" }
        .orUse { minimum }
        .build()
}

fun <T: Number> CollectorContext.requireGreaterThanOrEqualOrDrop(name: String, value: T, minimum: T, ruleViolation: RuleViolation, message: ((T, T) -> String)? = null): T? {
    val valueInBigDecimal = value.toBigDecimal()
    val minimumInBigDecimal = minimum.toBigDecimal()
    return check<T?>(name = name, value = value, isValid = { valueInBigDecimal >= minimumInBigDecimal })
        .violation { ruleViolation }
        .message { message?.invoke(value, minimum) ?: "$name must be greater than or equal to $minimum" }
        .orUse { null }
        .build()
}
