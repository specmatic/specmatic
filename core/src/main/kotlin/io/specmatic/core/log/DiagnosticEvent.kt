package io.specmatic.core.log

import io.specmatic.core.FailureReport
import io.specmatic.core.Result
import io.specmatic.core.pattern.ContractException
import java.time.Instant

enum class DiagnosticFlow {
    TEST,
    STUB,
    PROXY,
    EXAMPLES,
    BACKWARD_COMPATIBILITY,
    LIBRARY,
    UNKNOWN,
    ;

    companion object {
        fun from(mode: ExecutionMode): DiagnosticFlow {
            return when (mode) {
                ExecutionMode.TEST -> TEST
                ExecutionMode.STUB -> STUB
                ExecutionMode.PROXY -> PROXY
                ExecutionMode.EXAMPLES -> EXAMPLES
                ExecutionMode.BACKWARD_COMPATIBILITY -> BACKWARD_COMPATIBILITY
                ExecutionMode.LIBRARY -> LIBRARY
                ExecutionMode.UNKNOWN -> UNKNOWN
            }
        }
    }
}

object DiagnosticTagFormatter {
    fun forFlow(flow: DiagnosticFlow): String {
        return when (flow) {
            DiagnosticFlow.TEST -> "Specmatic::Test"
            DiagnosticFlow.STUB -> "Specmatic::Stub"
            DiagnosticFlow.PROXY -> "Specmatic::Proxy"
            DiagnosticFlow.EXAMPLES -> "Specmatic::Examples"
            DiagnosticFlow.BACKWARD_COMPATIBILITY -> "Specmatic::Backward Compatibility"
            DiagnosticFlow.LIBRARY -> "Specmatic::Library"
            DiagnosticFlow.UNKNOWN -> "Specmatic::Unknown"
        }
    }
}

enum class DiagnosticPhase(val label: String, val section: DiagnosticSection) {
    TEST_RUN_START("RUN", DiagnosticSection.PREPARING_TESTS),
    CONTRACT_DISCOVERY("DISCOVER", DiagnosticSection.PREPARING_TESTS),
    CONTRACT_LOADING("CONTRACT", DiagnosticSection.PREPARING_TESTS),
    EXAMPLES("EXAMPLES", DiagnosticSection.PREPARING_TESTS),
    EXAMPLE_VALIDATION("VALIDATE", DiagnosticSection.PREPARING_TESTS),
    ENVIRONMENT("ENV", DiagnosticSection.PREPARING_TESTS),
    SCENARIO_START("SCENARIO", DiagnosticSection.RUNNING_SCENARIOS),
    SCENARIO_RESULT("RESULT", DiagnosticSection.RUNNING_SCENARIOS),
    RUN_SUMMARY("SUMMARY", DiagnosticSection.SUMMARY),
    INTERNAL_ERROR("INTERNAL", DiagnosticSection.SUMMARY),
}

enum class DiagnosticSection(val title: String) {
    PREPARING_TESTS("Preparing Tests"),
    RUNNING_SCENARIOS("Running Scenarios"),
    SUMMARY("Summary"),
}

enum class DiagnosticSeverity {
    INFO,
    WARN,
    ERROR,
}

enum class DiagnosticKind {
    PROGRESS,
    DETAIL,
    FAILURE,
    SUMMARY,
}

sealed interface DiagnosticPayload {
    data class FailurePayload(
        val failure: Result.Failure,
    ) : DiagnosticPayload

    data class ReportPayload(
        val report: FailureReport,
    ) : DiagnosticPayload

    data class ContractExceptionPayload(
        val exception: ContractException,
    ) : DiagnosticPayload

    data class ThrowablePayload(
        val throwable: Throwable,
    ) : DiagnosticPayload
}

data class DiagnosticEvent(
    val flow: DiagnosticFlow = DiagnosticFlow.from(LoggingScope.executionContext().mode),
    val tag: String? = null,
    val phase: DiagnosticPhase,
    val kind: DiagnosticKind = DiagnosticKind.PROGRESS,
    val severity: DiagnosticSeverity,
    val summary: String,
    val details: String? = null,
    val detailTitle: String? = null,
    val remediation: String? = null,
    val executionContext: ExecutionContext = LoggingScope.executionContext(),
    val context: Map<String, String> = emptyMap(),
    val payload: DiagnosticPayload? = null,
    val timestamp: Instant = Instant.now(),
    val threadName: String = Thread.currentThread().name,
)
