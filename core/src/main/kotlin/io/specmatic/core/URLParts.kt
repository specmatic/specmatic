package io.specmatic.core

private const val MIN_URL_PARTS_WHEN_PATH_EXISTS = 4
private const val QUERY_PARAM_START_CHAR = '?'

class URLParts(url: String) {
    private val queryStartIndex = url.indexOf(QUERY_PARAM_START_CHAR)
    private val baseUrl = if (queryStartIndex != -1) url.substring(0, queryStartIndex) else url

    val parts = baseUrl.split("/", limit = MIN_URL_PARTS_WHEN_PATH_EXISTS)

    private val queryOnwards = if (queryStartIndex != -1) url.substring(queryStartIndex) else ""

    fun withEncodedPathSegments(): String {
        if(noPathInURL())
            return baseUrl

        val (scheme, _, authority, path) = parts

        val escapedPath = escapeSpaceInPath(path)

        return "$scheme//$authority/$escapedPath$queryOnwards"
    }

    fun withDecodedPathSegments(): String {
        if(noPathInURL())
            return baseUrl

        val (scheme, _, authority, path) = parts

        val escapedPath = decodePath(path)

        return "$scheme//$authority/$escapedPath$queryOnwards"
    }

    private fun noPathInURL() = parts.size < MIN_URL_PARTS_WHEN_PATH_EXISTS
}

// URL schemes are case-insensitive (RFC 3986), so compare them ignoring case.
fun String?.isHttpScheme(): Boolean = equals("http", ignoreCase = true) || equals("https", ignoreCase = true)