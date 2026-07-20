package application.mcp.server.tools

private const val CONTAINER_ROOT = "/usr/src/app"

class McpPathMapper {
    fun translate(path: String?): String? {
        if (path.isNullOrBlank()) return path
        if (path.startsWith(CONTAINER_ROOT)) return path

        val sourcePrefix = path.normalizedForMatch()?.trimEnd('/') ?: return path
        val destinationPrefix = CONTAINER_ROOT

        val normalizedPath = path.normalizedForMatch()
        if (normalizedPath == sourcePrefix) {
            return destinationPrefix
        }

        val pathPrefix = "$sourcePrefix/"
        if (!normalizedPath.startsWith(pathPrefix)) return path

        val suffix = normalizedPath.removePrefix(sourcePrefix)
        return destinationPrefix + suffix
    }
}

private fun String.normalizedForMatch(): String = replace('\\', '/')
