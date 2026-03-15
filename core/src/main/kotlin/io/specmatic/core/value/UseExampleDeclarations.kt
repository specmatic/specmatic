package io.specmatic.core.value

import io.specmatic.core.ExampleDeclarations
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.isPatternToken

data class UseExampleDeclarations(override val examples: Map<String, String> = emptyMap(), override val messages: List<String> = emptyList(), override val comment: String? = null) : ExampleDeclarations {
    override fun plus(more: ExampleDeclarations): ExampleDeclarations {
        val duplicateMessage = messageWhenDuplicateKeysExist(more, examples)
        duplicateMessage.forEach { logger.log(it) }

        return this.copy(examples = examples.plus(more.examples.filterNot { isPatternToken(it.value) }), messages = messages.plus(more.messages).plus(duplicateMessage))
    }

    override fun plus(more: Pair<String, String>): ExampleDeclarations = when {
        !isPatternToken(more.second) || more.second == "(null)"-> this.copy(examples = examples.plus(more))
        else -> this
    }

    override fun getNewName(typeName: String, keys: Collection<String>): String =
            generateSequence(typeName) { "${it}_" }.first { it !in keys }

    override fun withComment(comment: String?): ExampleDeclarations {
        return this.copy(comment = comment)
    }
}

internal fun messageWhenDuplicateKeysExist(newExampleDeclarations: ExampleDeclarations, examples: Map<String, String>): List<String> {
    val duplicateKeys = newExampleDeclarations.examples.keys.filter { it in examples }.filter { key ->
        val oldValue = examples.getValue(key)
        val newValue = newExampleDeclarations.examples.getValue(key)

        oldValue != newValue
    }

    return when {
        duplicateKeys.isNotEmpty() -> {
            val keysCsv = duplicateKeys.joinToString(", ")
            listOf("Duplicate keys with different values found: $keysCsv")
        }
        else -> emptyList()
    }
}
