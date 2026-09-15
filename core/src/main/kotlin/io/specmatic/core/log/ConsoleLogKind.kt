package io.specmatic.core.log

/**
 * Marker for [consoleLog] categories. Downstream may define its own kinds.
 * Under an installed [AgentConsoleLogger], kinds with [suppressUnderAgent] true are
 * kept out of stdout (LogTail still receives them via [consoleLog]).
 * Without the agent logger, every kind prints.
 */
interface ConsoleLogEmission {
    val suppressUnderAgent: Boolean
}

enum class ConsoleLogKind(
    override val suppressUnderAgent: Boolean,
) : ConsoleLogEmission {
    Default(false),
    HttpDump(true),
    StubTraffic(true),
}
