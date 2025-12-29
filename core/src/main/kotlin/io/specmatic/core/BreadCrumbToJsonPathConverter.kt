package io.specmatic.core

interface TransformationStrategy {
    fun transform(input: String): String

    data class ExactReplacement(private val from: String, private val to: String, private val ignoreCase: Boolean = true) : TransformationStrategy {
        override fun transform(input: String): String = if (input.equals(from, ignoreCase = ignoreCase)) to else input
    }

    data class DirectReplacement(private val from: String, private val to: String, private val ignoreCase: Boolean = true) : TransformationStrategy {
        override fun transform(input: String): String = input.replace(from, to, ignoreCase = ignoreCase)
    }

    data class RegexReplacement(private val pattern: Regex, private val transform: (MatchResult) -> CharSequence) : TransformationStrategy {
        override fun transform(input: String): String = pattern.replace(input, transform)
    }
}

data class TransformationConfig(val transformations: List<TransformationStrategy>, val jsonPathDelimiter: String = JSONPATH_DELIMITER) {
    companion object {
        const val JSONPATH_DELIMITER = "/"
    }
}

class BreadCrumbToJsonPathConverter(private val config: TransformationConfig = defaultConfig) {
    fun toJsonPath(breadcrumbs: List<String>): String {
        val components = convert(breadcrumbs)
        return buildJsonPath(components)
    }

    fun convert(breadcrumbs: List<String>): List<String> {
        return breadcrumbs.flatMap { it.splitBySeparators() }
            .filter(String::isNotBlank).map(::applyTransformations)
            .filter(String::isNotBlank).filterNot(::isTildeBreadCrumb)
    }

    private fun applyTransformations(breadcrumb: String): String {
        return config.transformations.fold(breadcrumb) { acc, mapping ->
            mapping.transform(acc)
        }.trimStart('/')
    }

    private fun buildJsonPath(components: List<String>): String {
        return components.joinToString(separator = config.jsonPathDelimiter, prefix = config.jsonPathDelimiter)
    }

    private fun isTildeBreadCrumb(component: String): Boolean {
        return (component.startsWith("(") && component.endsWith(")")) || component.startsWith("(~~~")
    }

    private fun String.splitBySeparators(): List<String> {
        return this.replace(ARRAY_START_BRACKET, "|$ARRAY_START_BRACKET").split(".", "|")
    }

    companion object {
        private const val ARRAY_START_BRACKET = "["
        val defaultConfig = TransformationConfig(
            transformations = listOf(
                // Parameters
                TransformationStrategy.ExactReplacement(BreadCrumb.PARAMETERS.value, ""),

                // RESPONSE / REQUEST / BODY
                TransformationStrategy.ExactReplacement("RESPONSE", "http-response"),
                TransformationStrategy.ExactReplacement("REQUEST", "http-request"),
                TransformationStrategy.ExactReplacement("BODY", "body"),
                TransformationStrategy.ExactReplacement("STATUS", "status"),

                // PATH variants
                TransformationStrategy.ExactReplacement(BreadCrumb.PARAM_PATH.value, "path"),
                TransformationStrategy.ExactReplacement(BreadCrumb.PATH.value, "path"),

                // HEADERS variants
                TransformationStrategy.ExactReplacement(BreadCrumb.PARAM_HEADER.value, "header"),
                TransformationStrategy.ExactReplacement(BreadCrumb.HEADER.value, "header"),

                // QUERY variants
                TransformationStrategy.ExactReplacement(BreadCrumb.PARAM_QUERY.value, "query"),
                TransformationStrategy.ExactReplacement(BreadCrumb.QUERY.value, "query"),

                // Tilde transformations
                TransformationStrategy.DirectReplacement(".(~~~", " (when "),
                TransformationStrategy.RegexReplacement(Regex("^\\(~~~")) { "(when " },

                // Indices transformations
                TransformationStrategy.RegexReplacement(Regex("\\[(\\d+)]")) { match -> "/${match.groupValues[1]}" }
            )
        )
    }
}
