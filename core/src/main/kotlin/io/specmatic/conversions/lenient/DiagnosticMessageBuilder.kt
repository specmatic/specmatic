package io.specmatic.conversions.lenient

import io.specmatic.core.Result
import io.specmatic.core.RuleViolation

interface DiagnosticMessage<T> {
    fun violation(violation: () -> RuleViolation?): DiagnosticMessage<T>
    fun message(text: () -> String): DiagnosticFallback<T>
}

interface DiagnosticFallback<T> {
    fun orUse(fallback: () -> T): DiagnosticReady<T>
}

interface DiagnosticReady<T> {
    fun build(isWarning: Boolean = false): T
}

data class DiagnosticMessageBuilder<T>(
    private val ctx: CollectorContext,
    private val name: String? = null,
    private val value: T?,
    private val isValid: Boolean,
    private val ruleViolation: () -> RuleViolation? = { null },
) : DiagnosticMessage<T> {
    override fun violation(violation: () -> RuleViolation?): DiagnosticMessageBuilder<T> {
        return this.copy(ruleViolation = violation)
    }

    override fun message(text: () -> String): DiagnosticFallback<T> {
        return DiagnosticFallbackBuilder(ctx, name, value, isValid, text, ruleViolation)
    }
}

private class DiagnosticFallbackBuilder<T>(
    private val ctx: CollectorContext,
    private val name: String? = null,
    private val value: T?,
    private val isValid: Boolean,
    private val message: () -> String,
    private val ruleViolation: () -> RuleViolation? = { null },
) : DiagnosticFallback<T> {
    override fun orUse(fallback: () -> T): DiagnosticReady<T> {
        return DiagnosticReadyBuilder(ctx, name, value, isValid, message, fallback, ruleViolation)
    }
}

private class DiagnosticReadyBuilder<T>(
    private val ctx: CollectorContext,
    private val name: String? = null,
    private val value: T?,
    private val isValid: Boolean,
    private val message: () -> String,
    private val fallback: () -> T,
    private val ruleViolation: () -> RuleViolation? = { null },
) : DiagnosticReady<T> {
    override fun build(isWarning: Boolean): T {
        if (value != null && isValid) return value
        val targetPath = name?.let(ctx::buildPath) ?: ctx.pathOrRoot
        val failure = Result.Failure(message = message(), breadCrumb = targetPath, isPartial = isWarning, ruleViolation = ruleViolation())
        ctx.recordEntry(failure)
        return fallback()
    }
}
