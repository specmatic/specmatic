package io.specmatic.core.log

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import io.specmatic.core.FailureReport
import io.specmatic.core.utilities.exceptionCauseMessage

class MordantDiagnosticLogger(
    private val consoleEnabled: Boolean = true,
    private val terminal: Terminal = Terminal(),
) : DiagnosticLogger {
    private val lock = Any()

    override fun emit(event: DiagnosticEvent) {
        if (!consoleEnabled) return

        synchronized(lock) {
            terminal.println(render(event))
        }
    }

    private fun render(event: DiagnosticEvent): String {
        val header = styleHeader(event)

        val lines = buildList {
            add(header)
            event.context.forEach { (key, value) ->
                if (value.isNotBlank()) {
                    add("  ${TextStyles.dim(prettyLabel(key))}: $value")
                }
            }
            event.details?.takeIf(String::isNotBlank)?.let {
                val detailLabel = event.detailTitle
                if (detailLabel != null) {
                    add("  ${TextStyles.bold(detailLabel)}:")
                    add(it.prependIndent("    "))
                } else {
                    add(it.prependIndent("  "))
                }
            }
            renderPayload(event.payload)?.let(::add)
            event.remediation?.takeIf(String::isNotBlank)?.let {
                add("\n")
                add("  ${TextStyles.bold("How to fix")}: $it")
                add("\n")
            }
        }

        return lines.joinToString(System.lineSeparator())
    }

    private fun styleHeader(event: DiagnosticEvent): String {
        val flowPrefix = when {
            event.tag == null -> "[${DiagnosticTagFormatter.forFlow(event.flow)}] "
            event.tag.isBlank() -> ""
            else -> "[${event.tag}] "
        }
        val prefix = when (event.severity) {
            DiagnosticSeverity.INFO -> ""
            DiagnosticSeverity.WARN -> "Warning: "
            DiagnosticSeverity.ERROR -> "Error: "
        }

        return styleForSeverity(event.severity, event.kind)(flowPrefix + prefix + event.summary)
    }

    private fun renderPayload(payload: DiagnosticPayload?): String? {
        return when (payload) {
            null -> null
            is DiagnosticPayload.FailurePayload -> renderReport(payload.failure.toFailureReport())
            is DiagnosticPayload.ReportPayload -> renderReport(payload.report)
            is DiagnosticPayload.ContractExceptionPayload -> renderReport(payload.exception.failure().toFailureReport())
            is DiagnosticPayload.ThrowablePayload -> "  ${TextStyles.bold("Cause")}: ${exceptionCauseMessage(payload.throwable)}"
        }
    }

    private fun renderReport(report: FailureReport): String {
        val reportHeader = if (report.hasOnlyWarnings()) TextColors.yellow("  Diagnostic") else TextColors.red("  Diagnostic")
        return buildString {
            append(reportHeader)
            append(System.lineSeparator())
            append(report.toText().prependIndent("    "))
        }
    }

    private fun prettyLabel(key: String): String {
        return key.split(" ").joinToString(" ") { token ->
            token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
    private fun styleForSeverity(severity: DiagnosticSeverity, kind: DiagnosticKind) = when (severity) {
        DiagnosticSeverity.INFO -> if (kind == DiagnosticKind.DETAIL) TextStyles.dim + TextColors.brightBlue else TextColors.cyan
        DiagnosticSeverity.WARN -> TextStyles.bold + TextColors.yellow
        DiagnosticSeverity.ERROR -> TextStyles.bold + TextColors.red
    }

    private fun styleForPhase(kind: DiagnosticKind) = when (kind) {
        DiagnosticKind.DETAIL -> TextStyles.dim
        else -> TextStyles.bold
    }
}
