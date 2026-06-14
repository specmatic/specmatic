package io.specmatic.core

import io.specmatic.core.pattern.ContractException

data class ObjectQuerySyntax(
    val root: ObjectQueryRoot,
    val propertyStyle: QueryPropertyStyle,
    val arrayIndexStyle: QueryArrayIndexStyle
) {
    companion object {
        val Default = ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)

        fun supportedSyntaxes(): List<ObjectQuerySyntax> {
            return ObjectQueryRoot.entries.flatMap { root ->
                QueryPropertyStyle.entries.map { propertyStyle ->
                    ObjectQuerySyntax(root, propertyStyle, QueryArrayIndexStyle.Bracket)
                }
            }
        }
    }
}

enum class ObjectQueryRoot(
    val displayName: String,
    internal val allowBareStart: Boolean,
    private val wrapperSeparator: String?
) {
    ParameterNameWrapped("parameter-name bracket wrapping", allowBareStart = false, wrapperSeparator = "[") {
        override fun keyForRootProperty(parameterName: String, propertyName: String): String = "$parameterName[$propertyName]"
    },
    ParameterNameDotWrapped("parameter-name dot wrapping", allowBareStart = false, wrapperSeparator = ".") {
        override fun keyForRootProperty(parameterName: String, propertyName: String): String = "$parameterName.$propertyName"
    },
    Unwrapped("unwrapped query keys", allowBareStart = true, wrapperSeparator = null) {
        override fun keyForRootProperty(parameterName: String, propertyName: String): String = propertyName
    };

    abstract fun keyForRootProperty(parameterName: String, propertyName: String): String

    internal fun isExplicitRootFor(key: String, parameterName: String): Boolean {
        val prefix = wrapperPrefix(parameterName) ?: return false
        return key.startsWith(prefix)
    }

    internal fun tokenizerRemainder(key: String, parameterName: String): String {
        if (wrapperSeparator == null) return key

        if (!isExplicitRootFor(key, parameterName)) {
            throw ContractException("Expected query key $key to be wrapped by parameter $parameterName")
        }

        return key.removePrefix(parameterName)
    }

    private fun wrapperPrefix(parameterName: String): String? {
        return wrapperSeparator?.let { separator -> "$parameterName$separator" }
    }

    companion object {
        fun explicitRootFor(key: String, parameterName: String): ObjectQueryRoot? {
            return entries.firstOrNull { root -> root.isExplicitRootFor(key, parameterName) }
        }
    }
}

enum class QueryPropertyStyle(val displayName: String) {
    Bracket("bracket property notation") {
        override fun appendProperty(key: String, propertyName: String): String = "$key[$propertyName]"
    },
    Dot("dot property notation") {
        override fun appendProperty(key: String, propertyName: String): String = "$key.$propertyName"
    };

    abstract fun appendProperty(key: String, propertyName: String): String
}

enum class QueryArrayIndexStyle {
    Bracket {
        override fun appendIndex(key: String, index: Int): String = "$key[$index]"
    };

    abstract fun appendIndex(key: String, index: Int): String
}
