package io.specmatic.core.log

/**
 * Suite-scoped quiet console: wraps the existing [LogStrategy] and suppresses only
 * [ConsoleLogKind]s marked [ConsoleLogKind.suppressUnderAgent]. Default logs still print.
 */
class AgentConsoleLogger(val delegate: LogStrategy) : LogStrategy by delegate {
    fun shouldPrint(kind: ConsoleLogKind): Boolean = !kind.suppressUnderAgent
}
