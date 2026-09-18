package io.specmatic.core.log

/**
 * Suite-scoped quiet console: wraps the existing [LogStrategy] and suppresses only
 * [ConsoleLogEmissionPolicy]s marked [ConsoleLogEmissionPolicy.suppressUnderAgent]. Default logs still print.
 */
class AgentConsoleLogger(val delegate: LogStrategy) : LogStrategy by delegate {
    override fun shouldPrintToConsole(kind: ConsoleLogEmissionPolicy): Boolean =
        !kind.suppressUnderAgent
}
