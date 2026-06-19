package io.specmatic.core

sealed class NestedQuerySchema {
    fun schemaAt(path: QueryObjectPath): NestedQuerySchema? {
        return schemaAt(path.tokens)
    }

    private fun schemaAt(tokens: List<QueryObjectPathToken>): NestedQuerySchema? {
        if (tokens.isEmpty()) return this

        return childSchema(tokens.first())?.schemaAt(tokens.drop(1))
    }

    protected abstract fun childSchema(token: QueryObjectPathToken): NestedQuerySchema?
    internal abstract fun requiresSyntaxExampleAtPath(): Boolean
    internal abstract fun requiresPropertyStyleGuidance(): Boolean

    data object Scalar : NestedQuerySchema() {
        override fun childSchema(token: QueryObjectPathToken): NestedQuerySchema? = null
        override fun requiresSyntaxExampleAtPath(): Boolean = false
        override fun requiresPropertyStyleGuidance(): Boolean = false
    }

    data class Object(
        val properties: Map<String, NestedQuerySchema>,
        val additionalProperties: NestedQuerySchema? = null,
        val allowsAnyAdditionalProperties: Boolean = false
    ) : NestedQuerySchema() {
        override fun childSchema(token: QueryObjectPathToken): NestedQuerySchema? {
            val property = token as? QueryObjectPathToken.Property ?: return null

            return properties[property.name]
                ?: additionalProperties
                ?: if (allowsAnyAdditionalProperties) Scalar else null
        }

        override fun requiresSyntaxExampleAtPath(): Boolean = true

        override fun requiresPropertyStyleGuidance(): Boolean {
            return properties.isNotEmpty() || additionalProperties != null || allowsAnyAdditionalProperties
        }

        fun requiresSyntaxExamples(): Boolean {
            return syntaxGuidanceChildSchemas().any { schema -> schema.requiresSyntaxExampleAtPath() }
        }

        fun requiresNestedPropertyStyle(): Boolean {
            return syntaxGuidanceChildSchemas().any { schema -> schema.requiresPropertyStyleGuidance() } ||
                allowsAnyAdditionalProperties
        }

        internal fun couldOwnQueryKey(key: String, parameterName: String): Boolean {
            return ObjectQueryRoot.explicitRootFor(key, parameterName) != null || couldStartWithRootProperty(key)
        }

        internal fun couldStartWithRootProperty(key: String): Boolean {
            return properties.keys.any { propertyName ->
                key == propertyName || key.startsWith("$propertyName.") || key.startsWith("$propertyName[")
            }
        }

        private fun syntaxGuidanceChildSchemas(): List<NestedQuerySchema> {
            return properties.values.toList() + listOfNotNull(additionalProperties)
        }
    }

    data class Array(val itemSchema: NestedQuerySchema) : NestedQuerySchema() {
        override fun childSchema(token: QueryObjectPathToken): NestedQuerySchema? {
            if (token !is QueryObjectPathToken.Index) return null

            return itemSchema
        }

        override fun requiresSyntaxExampleAtPath(): Boolean = true
        override fun requiresPropertyStyleGuidance(): Boolean = itemSchema.requiresPropertyStyleGuidance()
    }

    data class Ambiguous(val reason: String) : NestedQuerySchema() {
        override fun childSchema(token: QueryObjectPathToken): NestedQuerySchema? = null
        override fun requiresSyntaxExampleAtPath(): Boolean = true
        override fun requiresPropertyStyleGuidance(): Boolean = true
    }
}
