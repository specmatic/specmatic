package io.specmatic.core.log

import io.specmatic.core.Result
import io.specmatic.core.pattern.ContractException
import java.io.File

object SpecificationPreparationDiagnostics {
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
    ) {
        val executionContext = LoggingScope.executionContext()
        LoggingScope.diagnosticLogger().emit(
            DiagnosticEvent(
                flow = DiagnosticFlow.from(executionContext.mode),
                phase = phase,
                kind = kind,
                severity = severity,
                summary = summary,
                details = details,
                detailTitle = detailTitle,
                remediation = remediation,
                executionContext = executionContext,
                context = context,
                payload = payload,
            )
        )
    }

    fun specificationPreparationDetail(
        summary: String,
        details: String? = null,
        context: Map<String, String> = emptyMap(),
    ) {
        emit(
            phase = DiagnosticPhase.CONTRACT_LOADING,
            kind = DiagnosticKind.DETAIL,
            severity = DiagnosticSeverity.INFO,
            summary = summary,
            details = details,
            detailTitle = null,
            context = context,
        )
    }

    fun specificationLoaded(
        contractPath: String,
        details: String,
    ) {
        if (!shouldDisplayContract(contractPath)) return

        emit(
            phase = DiagnosticPhase.CONTRACT_LOADING,
            kind = DiagnosticKind.DETAIL,
            severity = DiagnosticSeverity.INFO,
            summary = "Loaded specification ${displayName(contractPath)}",
            details = compactContractSummary(details),
            detailTitle = "Specification summary",
            context = mapOf("specification" to contractPath),
        )
    }

    fun specificationParseWarning(
        contractPath: String,
        parser: String,
        messages: String,
        remediation: String,
        details: String? = null,
        context: Map<String, String> = emptyMap(),
    ) {
        if (!shouldDisplayContract(contractPath)) return

        emit(
            phase = DiagnosticPhase.CONTRACT_LOADING,
            kind = DiagnosticKind.FAILURE,
            severity = DiagnosticSeverity.WARN,
            summary = "Loaded specification ${displayName(contractPath)} with parser warnings",
            details = listOfNotNull(messages.takeIf(String::isNotBlank), details?.takeIf(String::isNotBlank))
                .joinToString(System.lineSeparator() + System.lineSeparator())
                .takeIf(String::isNotBlank),
            detailTitle = "Parser warnings",
            remediation = remediation,
            context = mapOf(
                "specification" to contractPath,
                "parser" to parser,
            ) + context,
        )
    }

    fun specificationParseFailed(
        contractPath: String,
        parser: String,
        messages: String?,
        remediation: String,
        details: String? = null,
        throwable: Throwable? = null,
        context: Map<String, String> = emptyMap(),
    ) {
        if (!shouldDisplayContract(contractPath)) return

        val emitEvent = {
            emit(
                phase = DiagnosticPhase.CONTRACT_LOADING,
                kind = DiagnosticKind.FAILURE,
                severity = DiagnosticSeverity.ERROR,
                summary = "Could not parse specification ${displayName(contractPath)}",
                details = listOfNotNull(messages?.takeIf(String::isNotBlank), details?.takeIf(String::isNotBlank))
                    .joinToString(System.lineSeparator() + System.lineSeparator())
                    .takeIf(String::isNotBlank),
                detailTitle = "Parser warnings",
                remediation = remediation,
                context = mapOf(
                    "specification" to contractPath,
                    "parser" to parser,
                ) + context,
                payload = throwable?.let(::payloadFor),
            )
        }

        if (throwable != null) {
            emitThrowableFailureIfNotLogged(throwable, emitEvent)
        } else {
            emitEvent()
        }
    }

    fun externalExamplesDiscovered(exampleDirectory: String, exampleCount: Int) {
        emit(
            phase = DiagnosticPhase.EXAMPLES,
            severity = DiagnosticSeverity.INFO,
            summary = "Loaded $exampleCount external example file(s)",
            context = mapOf(
                "directory" to exampleDirectory,
            ),
        )
    }

    fun externalExampleLoadFailed(exampleFile: String, throwable: Throwable, remediation: String) {
        emitThrowableFailureIfNotLogged(throwable) {
            emit(
                phase = DiagnosticPhase.EXAMPLES,
                kind = DiagnosticKind.FAILURE,
                severity = DiagnosticSeverity.ERROR,
                summary = "Could not load example ${File(exampleFile).name}",
                remediation = remediation,
                context = mapOf("example file" to exampleFile),
                payload = payloadFor(throwable),
            )
        }
    }

    fun unusedExamplesFound(contractPath: String, examplePaths: List<String>, details: String) {
        emit(
            phase = DiagnosticPhase.EXAMPLES,
            kind = DiagnosticKind.FAILURE,
            severity = DiagnosticSeverity.WARN,
            summary = "Some external examples were not used by ${File(contractPath).name}",
            details = details,
            detailTitle = "Why this was ignored",
            remediation = "Remove outdated example files or align them with an operation in the specification.",
            context = mapOf(
                "specification" to contractPath,
                "unused examples" to examplePaths.size.toString(),
            ),
        )
    }

    fun exampleValidationFailed(
        contractPath: String,
        failure: Result.Failure,
        remediation: String,
        invalidExampleFileCount: Int? = null,
        totalExampleFileCount: Int? = null,
    ) {
        emit(
            phase = DiagnosticPhase.EXAMPLE_VALIDATION,
            kind = DiagnosticKind.FAILURE,
            severity = DiagnosticSeverity.ERROR,
            summary = exampleValidationSummary(
                contractPath = contractPath,
                invalidExampleFileCount = invalidExampleFileCount,
                totalExampleFileCount = totalExampleFileCount,
            ),
            remediation = remediation,
            context = mapOf("specification" to contractPath),
            payload = DiagnosticPayload.FailurePayload(failure),
        )
    }

    fun examplePairingWarning(
        contractPath: String,
        exampleNames: List<String>,
        messages: List<String>,
        remediation: String,
    ) {
        if (!shouldDisplayContract(contractPath) || exampleNames.isEmpty()) return

        val count = exampleNames.size
        emit(
            phase = DiagnosticPhase.EXAMPLES,
            kind = DiagnosticKind.FAILURE,
            severity = DiagnosticSeverity.WARN,
            summary = if (count == 1) {
                "Inline example ${exampleNames.first()} is incomplete in ${displayName(contractPath)}"
            } else {
                "$count inline examples are incomplete in ${displayName(contractPath)}"
            },
            details = messages.distinct().joinToString(System.lineSeparator()),
            detailTitle = "Why this was ignored",
            remediation = remediation,
            context = mapOf(
                "specification" to contractPath,
                "examples" to exampleNames.joinToString(", "),
            ),
        )
    }

    private fun compactContractSummary(details: String): String {
        return details.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.startsWith("API Specification Summary:") }
            .joinToString(System.lineSeparator())
    }

    private fun displayName(contractPath: String): String =
        contractPath.takeIf(String::isNotBlank)?.let { File(it).name } ?: "specification"

    private fun shouldDisplayContract(contractPath: String): Boolean =
        contractPath.isNotBlank()

    private fun payloadFor(throwable: Throwable): DiagnosticPayload {
        return when (throwable) {
            is ContractException -> DiagnosticPayload.ContractExceptionPayload(throwable)
            else -> DiagnosticPayload.ThrowablePayload(throwable)
        }
    }

    private fun exampleValidationSummary(
        contractPath: String,
        invalidExampleFileCount: Int?,
        totalExampleFileCount: Int?,
    ): String {
        val invalidCount = invalidExampleFileCount?.takeIf { it > 0 }
        val totalCount = totalExampleFileCount?.takeIf { it > 0 }

        if (invalidCount == null || totalCount == null) {
            return "Examples did not validate for specification ${File(contractPath).name}"
        }

        val exampleLabel = if (totalCount == 1) "external example is" else "external examples are"
        return "$invalidCount of $totalCount $exampleLabel invalid for ${File(contractPath).name}"
    }
}
