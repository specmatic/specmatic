package io.specmatic.core.fuzzy

interface StringNormalizer {
    fun normalize(input: String): String

    class AlphanumericNormalizer : StringNormalizer {
        override fun normalize(input: String): String = input.lowercase().replace(normalizeRegex, "")

        companion object {
            private val normalizeRegex = Regex("[^a-z0-9]")
        }
    }
}

interface StringTokenizer {
    fun tokenize(input: String): List<String>

    class StandardTokenizer : StringTokenizer {
        override fun tokenize(input: String): List<String> {
            return input.split(splitter).asSequence().filter(String::isNotEmpty).map(String::lowercase).toList()
        }

        companion object {
            private val splitter = Regex("[^a-zA-Z0-9]|(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
        }
    }
}
