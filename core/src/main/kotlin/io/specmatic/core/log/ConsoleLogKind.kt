package io.specmatic.core.log

/**
 * Policy for a [consoleLog] category. Downstream may define its own kinds.
 * Under an installed [AgentConsoleLogger], kinds with [suppressUnderAgent] true are
 * kept out of stdout (LogTail still receives them via [consoleLog]).
 * Without the agent logger, every kind prints.
 */
interface ConsoleLogEmissionPolicy {
    val suppressUnderAgent: Boolean
}

enum class ConsoleLogKind(
    override val suppressUnderAgent: Boolean,
) : ConsoleLogEmissionPolicy {
    Default(false),
    HttpDump(true),
    StubTraffic(true),
}
