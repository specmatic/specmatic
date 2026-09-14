package io.specmatic.core.log

/**
 * Category for console emissions. Under an installed [AgentConsoleLogger], kinds with
 * [suppressUnderAgent] true are kept out of stdout (LogTail still receives them via [consoleLog]).
 * Without the agent logger, every kind prints.
 */
enum class ConsoleLogKind(val suppressUnderAgent: Boolean) {
    Default(false),
    HttpDump(true),
    FixtureDetail(true),
    StubTraffic(true),
    AsyncTraffic(true),
}
