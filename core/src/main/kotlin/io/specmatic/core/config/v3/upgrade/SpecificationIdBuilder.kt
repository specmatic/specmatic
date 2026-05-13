package io.specmatic.core.config.v3.upgrade

import java.security.MessageDigest

internal fun buildSpecificationIds(specPaths: Collection<String>): Map<String, String> {
    val normalizedPaths = specPaths.map { it.normalizeSpecPath() }
    return normalizedPaths.associate { path -> path.original to generateSpecificationId(path, normalizedPaths) }
}

private data class NormalizedSpecPath(val original: String, val segments: List<String>)
private fun String.normalizeSpecPath(): NormalizedSpecPath {
    val normalized = replace('\\', '/').trimEnd('/')
    val segments = normalized.split('/').filter { it.isNotBlank() }
    return NormalizedSpecPath(original = this, segments = segments)
}

private fun generateSpecificationId(path: NormalizedSpecPath, allPaths: List<NormalizedSpecPath>): String {
    val maxDepth = path.segments.lastIndex
    for (depth in 0..maxDepth) {
        val candidate = candidateAtDepth(path.segments, depth)
        if (allPaths.count { candidateAtDepth(it.segments, depth) == candidate } == 1) {
            return candidate
        }
    }

    return candidateAtDepth(path.segments, maxDepth) + "-" + shortHash(path.original)
}

private fun candidateAtDepth(segments: List<String>, depth: Int): String {
    val startIndex = (segments.size - 1 - depth).coerceAtLeast(0)
    return segments.drop(startIndex).joinToString("-") { sanitizeIdPart(it) }
}

private fun sanitizeIdPart(value: String): String {
    val withoutExt = value.substringBeforeLast('.')
    return withoutExt.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "spec" }
}

private fun shortHash(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
    return bytes.take(4).joinToString("") { "%02x".format(it) }
}
