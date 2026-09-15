package io.specmatic.core.log

/**
 * Suite-scoped quiet console: wraps the existing [LogStrategy] and suppresses only
 * [ConsoleLogEmission]s marked [ConsoleLogEmission.suppressUnderAgent]. Default logs still print.
 */
class AgentConsoleLogger(val delegate: LogStrategy) : LogStrategy by delegate {
    override fun shouldPrintToConsole(kind: ConsoleLogEmission): Boolean =
        !kind.suppressUnderAgent
}
