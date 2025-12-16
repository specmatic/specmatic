package io.specmatic.core

interface RuleViolationContext {
    val context: String
    val groupId: Int
}

enum class StandardRuleViolationContext(override val context: String, override val groupId: Int): RuleViolationContext {
    TEST("test", 0),
    STUB("stub", 0),
    RESPONSE("response", 1),
    REQUEST("request", 1),
}
