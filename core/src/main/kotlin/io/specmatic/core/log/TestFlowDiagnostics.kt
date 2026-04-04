package io.specmatic.core.log

import io.specmatic.core.Result
import io.specmatic.core.pattern.ContractException
import java.io.File

object TestFlowDiagnostics {
    private const val TEST_TAG = "Specmatic::Test"

    private fun emit(
        phase: DiagnosticPhase,
        kind: DiagnosticKind = DiagnosticKind.PROGRESS,
        severity: DiagnosticSeverity,
        summary: String,
        details: String? = null,
        detailTitle: String? = null,
        remediation: String? = null,
        context: Map<String, String> = emptyMap(),
        payload: DiagnosticPayload? = null,
        tag: String? = TEST_TAG,
    ) {
        if (LoggingScope.executionContext().mode != ExecutionMode.TEST) return

        LoggingScope.diagnosticLogger().emit(
            DiagnosticEvent(
                flow = DiagnosticFlow.TEST,
                tag = tag,
                phase = phase,
                kind = kind,
                severity = severity,
                summary = summary,
                details = details,
                detailTitle = detailTitle,
                remediation = remediation,
                executionContext = LoggingScope.executionContext(),
                context = context,
                payload = payload,
            )
        )
    }

    fun specificationConfigLoadingStarted(configPath: String) {
        emit(
            phase = DiagnosticPhase.TEST_RUN_START,
            severity = DiagnosticSeverity.INFO,
            summary = "Loading specifications from ${File(configPath).name}",
            context = mapOf("specification config" to configPath),
        )
    }

    fun specificationConfigLoadFailed(configPath: String, throwable: Throwable, remediation: String) {
        emitThrowableFailureIfNotLogged(throwable) {
            emit(
                phase = DiagnosticPhase.TEST_RUN_START,
                kind = DiagnosticKind.FAILURE,
                severity = DiagnosticSeverity.ERROR,
                summary = "Could not load specification config ${File(configPath).name}",
                remediation = remediation,
                context = mapOf("specification config" to configPath),
                payload = payloadFor(throwable),
            )
        }
    }

    fun testRunStarted(filters: String?) {
        if (filters.isNullOrBlank()) return

        emit(
            phase = DiagnosticPhase.TEST_RUN_START,
            severity = DiagnosticSeverity.INFO,
            summary = "Applying test filters",
            context = mapOf("filters" to filters),
        )
    }

    fun contractLoadingStarted(contractPath: String, parser: String, overlayFilePath: String?, exampleDirectories: List<String>) {
        emit(
            phase = DiagnosticPhase.CONTRACT_LOADING,
            severity = DiagnosticSeverity.INFO,
            summary = "Loading specification ${contractPath.substringAfterLast('/')}",
            context = mapOfNotNull(
                "specification" to contractPath,
                "parser" to parser,
                "overlay" to overlayFilePath,
                "example directories" to exampleDirectories.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ),
        )
    }

    fun contractLoadFailed(contractPath: String, parser: String, throwable: Throwable, remediation: String) {
        emitThrowableFailureIfNotLogged(throwable) {
            emit(
                phase = DiagnosticPhase.CONTRACT_LOADING,
                kind = DiagnosticKind.FAILURE,
                severity = DiagnosticSeverity.ERROR,
                summary = "Could not prepare specification ${contractPath.substringAfterLast('/')}",
                remediation = remediation,
                context = mapOf(
                    "specification" to contractPath,
                    "parser" to parser,
                ),
                payload = payloadFor(throwable),
            )
        }
    }

    fun noTestsFound(reason: String) {
        emit(
            phase = DiagnosticPhase.EXAMPLE_VALIDATION,
            kind = DiagnosticKind.FAILURE,
            severity = DiagnosticSeverity.WARN,
            summary = "No runnable specification tests were produced",
            details = reason,
            remediation = "Adjust the filters or verify that the specifications actually contain runnable scenarios.",
        )
    }

    fun environmentProblem(summary: String, details: String? = null, remediation: String, context: Map<String, String> = emptyMap(), throwable: Throwable? = null) {
        val emitEvent = {
            emit(
                phase = DiagnosticPhase.ENVIRONMENT,
                kind = DiagnosticKind.FAILURE,
                severity = DiagnosticSeverity.ERROR,
                summary = summary,
                details = details,
                remediation = remediation,
                context = context,
                payload = throwable?.let { payloadFor(it) },
            )
        }

        if (throwable == null) emitEvent()
        else emitThrowableFailureIfNotLogged(throwable) { emitEvent() }
    }

    fun scenarioStarted(name: String, contractPath: String?, baseUrl: String?) {
        emit(
            phase = DiagnosticPhase.SCENARIO_START,
            severity = DiagnosticSeverity.INFO,
            summary = name.trim(),
            context = mapOfNotNull(
                "specification" to contractPath,
                "base url" to baseUrl,
            ),
        )
    }

    fun scenarioPassed(durationMs: Long?) {
        emit(
            phase = DiagnosticPhase.SCENARIO_RESULT,
            severity = DiagnosticSeverity.INFO,
            summary = "Test Result: PASSED",
            context = mapOfNotNull(
                "duration" to durationMs?.let { "${it}ms" },
            ),
        )
    }

    fun scenarioFailed(
        name: String,
        summary: String,
        remediation: String,
        contractPath: String?,
        failure: Result.Failure? = null,
        throwable: Throwable? = null,
        details: String? = null,
    ) {
        val emitEvent = {
            emit(
                phase = DiagnosticPhase.SCENARIO_RESULT,
                kind = DiagnosticKind.FAILURE,
                severity = DiagnosticSeverity.ERROR,
                summary = summary,
                details = details,
                detailTitle = "Why it failed",
                remediation = remediation,
                context = mapOfNotNull(
                    "scenario" to name,
                    "specification" to contractPath,
                ),
                payload = when {
                    failure != null -> DiagnosticPayload.FailurePayload(failure)
                    throwable != null -> payloadFor(throwable)
                    else -> null
                },
            )
        }

        if (throwable != null) {
            emitThrowableFailureIfNotLogged(throwable, emitEvent)
        } else {
            emitEvent()
        }
    }

    fun scenarioPartialSuccess(name: String, message: String, contractPath: String?) {
        emit(
            phase = DiagnosticPhase.SCENARIO_RESULT,
            kind = DiagnosticKind.FAILURE,
            severity = DiagnosticSeverity.WARN,
            summary = "PARTIAL $name",
            details = message,
            detailTitle = "Why it failed",
            remediation = "Review the warning above before relying on this scenario result.",
            context = mapOfNotNull(
                "scenario" to name,
                "specification" to contractPath,
            ),
        )
    }

    fun scenarioAborted(name: String, reason: String) {
        emit(
            phase = DiagnosticPhase.SCENARIO_RESULT,
            kind = DiagnosticKind.FAILURE,
            severity = DiagnosticSeverity.WARN,
            summary = "ABORTED $name",
            details = reason,
            detailTitle = "Why it failed",
            remediation = "Inspect the scenario configuration or WIP tags for this contract test.",
            context = mapOf("scenario" to name),
        )
    }

    fun internalError(summary: String, throwable: Throwable, remediation: String, context: Map<String, String> = emptyMap()) {
        emitThrowableFailureIfNotLogged(throwable) {
            emit(
                phase = DiagnosticPhase.INTERNAL_ERROR,
                kind = DiagnosticKind.FAILURE,
                severity = DiagnosticSeverity.ERROR,
                summary = summary,
                remediation = remediation,
                context = context,
                payload = payloadFor(throwable),
            )
        }
    }

    fun scenarioExecutionDetail(
        name: String,
        summary: String,
        details: String? = null,
        contractPath: String? = null,
        context: Map<String, String> = emptyMap(),
        detailTitle: String? = if (details.isNullOrBlank()) null else "Details",
    ) {
        emit(
            phase = DiagnosticPhase.SCENARIO_RESULT,
            kind = DiagnosticKind.DETAIL,
            severity = DiagnosticSeverity.INFO,
            summary = summary,
            details = details,
            detailTitle = detailTitle,
            context = mapOfNotNull(
                "scenario" to name,
                "specification" to contractPath,
            ) + context,
        )
    }

    fun httpInteraction(httpLogMessage: HttpLogMessage) {
        val requestPath = httpLogMessage.request.path ?: "/"
        val responseStatus = httpLogMessage.response?.status?.toString() ?: "no response"
        val request = httpLogMessage.request.toLogString(prettyPrint = httpLogMessage.prettyPrint).trim()
        val response = (httpLogMessage.response?.toLogString(prettyPrint = httpLogMessage.prettyPrint) ?: "No response").trim()

        emit(
            phase = DiagnosticPhase.SCENARIO_RESULT,
            kind = DiagnosticKind.DETAIL,
            severity = DiagnosticSeverity.INFO,
            summary = "HTTP ${httpLogMessage.request.method} $requestPath -> $responseStatus",
            details = listOf(
                "",
                "Request",
                request,
                "",
                "Response",
                response,
            ).joinToString(System.lineSeparator()),
            context = mapOfNotNull(
                "duration" to httpLogMessage.responseTime?.let { "${httpLogMessage.duration()}ms" },
                "comment" to httpLogMessage.comment,
            ),
            detailTitle = null,
        )
    }

    private fun emitReportOutput(output: String) {
        val reportText = output.trimEnd()
        if (reportText.isBlank()) return

        emit(
            phase = DiagnosticPhase.RUN_SUMMARY,
            kind = DiagnosticKind.DETAIL,
            severity = DiagnosticSeverity.INFO,
            summary = reportText,
            tag = "",
        )
    }

    fun reportOutputOrFallbackTo(output: String, fallback: () -> Unit) {
        if (isDiagnosticLoggingActive()) {
            emitReportOutput(output)
        } else {
            fallback()
        }
    }

    private fun payloadFor(throwable: Throwable): DiagnosticPayload {
        return when (throwable) {
            is ContractException -> DiagnosticPayload.ContractExceptionPayload(throwable)
            else -> DiagnosticPayload.ThrowablePayload(throwable)
        }
    }

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> {
        return pairs.mapNotNull { (key, value) -> value?.takeIf(String::isNotBlank)?.let { key to it } }.toMap()
    }
}
