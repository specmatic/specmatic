package io.specmatic.core.jsonoperator

import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.listFold

sealed interface PathSegment {
    val parsedPath: String

    data class Key(val key: String, override val parsedPath: String) : PathSegment {
        override fun toString(): String = key
    }

    data class Index(val index: Int, override val parsedPath: String) : PathSegment {
        override fun toString(): String = index.toString()
    }

    companion object {
        private val PATH_REGEX = Regex("""([^\[\].]+)|\[(-?\d+)]""")
        private const val JSON_POINTER_SEPARATOR = "/"

        fun parsePath(path: String): ReturnValue<List<PathSegment>> {
            if (path.isBlank()) return HasValue(emptyList())
            return when (path.contains(JSON_POINTER_SEPARATOR)) {
                true -> parseJsonPointer(path)
                false -> parseInternalJsonPointer(path)
            }.listFold()
        }

        private fun parseJsonPointer(path: String): List<ReturnValue<PathSegment>> {
            val normalizedPath = path.replace('#', '/').replace('.', '/')
            val segments = normalizedPath.split(JSON_POINTER_SEPARATOR).filter(String::isNotBlank)
            val decodedSegments = segments.map { it.replace("~1", "/").replace("~0", "~") }

            return decodedSegments.fold(emptyList()) { segments, rawSegment ->
                segments.plus(HasValue(
                    if (rawSegment.toIntOrNull() != null) {
                        Index(rawSegment.toInt(), path)
                    } else {
                        Key(rawSegment, path)
                    },
                ))
            }
        }

        private fun parseInternalJsonPointer(path: String): List<ReturnValue<PathSegment>> {
            val segments = PATH_REGEX.findAll(path)
            return segments.fold(emptyList()) { segments, rawMatchResult ->
                @Suppress("UNCHECKED_CAST")
                val nextSegment = when {
                    rawMatchResult.groups[1] != null -> Key(rawMatchResult.groupValues[1], path).let(::HasValue)
                    rawMatchResult.groups[2] != null -> Index(rawMatchResult.groupValues[2].toInt(), path).let(::HasValue)
                    else -> HasFailure("Invalid key segment: ${rawMatchResult.value}")
                } as ReturnValue<PathSegment>
                segments.plus(nextSegment)
            }
        }
    }
}

internal inline fun <reified T : PathSegment> Collection<PathSegment>.takeNextAs(): ReturnValue<T> {
    if (isEmpty()) throw IllegalStateException("Unexpected end of path")
    return when (val next = first()) {
        is T -> HasValue(next)
        else -> HasFailure("""
        Unexpected path segment '$next', expected: ${T::class.simpleName} got ${next::class.simpleName}
        """.trimIndent())
    }
}
