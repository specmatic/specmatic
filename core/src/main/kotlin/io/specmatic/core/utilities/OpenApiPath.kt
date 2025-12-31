package io.specmatic.core.utilities

data class OpenApiPath(private val parts: List<String>) {
    fun normalize(): OpenApiPath {
        return this.copy(
            parts = parts.map { segment ->
                if (isNumericPathSegment(segment)) "{id}" else segment
            }
        )
    }

    fun normalizedEquals(other: OpenApiPath): Boolean = this.normalize() == other.normalize()

    fun build(): String = parts.joinToString(separator = PATH_SEPARATOR, prefix = PATH_SEPARATOR)

    private fun isNumericPathSegment(segment: String): Boolean = segment.all(Char::isDigit)

    companion object {
        private const val PATH_SEPARATOR = "/"

        fun from(path: String): OpenApiPath {
            return OpenApiPath(path.split(PATH_SEPARATOR).filter(String::isNotBlank))
        }
    }
}
