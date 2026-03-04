package io.specmatic.core

import io.specmatic.core.pattern.ExactValuePattern

class PathSegmentExtractor(private val contractPath: String, private val pathSegmentPatterns: List<URLPathSegmentPattern>) {
    private val literals: List<String?> = pathSegmentPatterns.map { segment ->
        (segment.pattern as? ExactValuePattern)?.pattern?.toStringLiteral()
    }

    fun extract(rawPath: String): List<String> {
        val path = ensurePrefixAndSuffix(rawPath)
        val (segments, cursor) = pathSegmentPatterns.indices.fold(emptyList<String>() to 0) { (acc, cursor), index ->
            val literal = literals[index]
            val nextLiteral = nextLiteralFrom(index + 1)
            val end = if (literal != null) {
                literalEnd(path, cursor, literal, nextLiteral)
            } else {
                variableEnd(path, cursor, nextLiteral)
            }

            if (end <= cursor) return@fold acc to end
            acc.plus(path.substring(cursor, end)) to end
        }

        return if (cursor < path.length) {
            segments.plus(path.substring(cursor))
        } else {
            segments
        }
    }

    fun ensurePrefixAndSuffix(path: String): String {
        return path.normalizePrefix(contractPath).normalizeSuffix(contractPath).normalizeSlash()
    }

    private fun literalEnd(path: String, cursor: Int, literal: String, nextLiteral: String?): Int {
        if (path.startsWith(literal, cursor)) return cursor + literal.length

        val nextLiteralIndex = nextLiteral?.let { path.indexOf(it, cursor) } ?: -1
        if (nextLiteralIndex >= 0) return nextLiteralIndex

        val nextSlash = nextSlashOrEnd(path, cursor)
        return if (literal.endsWith("/") && nextSlash < path.length) nextSlash + 1 else nextSlash
    }

    private fun variableEnd(path: String, cursor: Int, nextLiteral: String?): Int {
        val nextLiteralIndex = nextLiteral?.let { path.indexOf(it, cursor) } ?: -1
        if (nextLiteralIndex >= 0) return nextLiteralIndex
        return nextSlashOrEnd(path, cursor)
    }

    private fun nextLiteralFrom(start: Int): String? {
        return literals.slice(start until literals.size).firstNotNullOfOrNull { it }
    }

    private fun nextSlashOrEnd(path: String, cursor: Int): Int {
        val nextSlash = path.indexOf('/', (cursor + 1).coerceAtMost(path.length))
        return if (nextSlash >= 0) nextSlash else path.length
    }

    private fun String.normalizePrefix(contractPath: String): String {
        return if (contractPath.startsWith('/')) ensurePrefix("/") else removePrefix("/")
    }

    private fun String.normalizeSuffix(contractPath: String): String {
        if (contractPath == "/") return this
        return if (contractPath.endsWith('/')) ensureSuffix("/") else removeSuffix("/")
    }

    private fun String.normalizeSlash(): String = replace(Regex("/{2,}"), "/")

    private fun String.ensurePrefix(prefix: String): String {
        return if (startsWith(prefix)) {
            this
        } else {
            prefix + this
        }
    }

    private fun String.ensureSuffix(suffix: String): String {
        return if (endsWith(suffix)) {
            this
        } else {
            this + suffix
        }
    }
}
