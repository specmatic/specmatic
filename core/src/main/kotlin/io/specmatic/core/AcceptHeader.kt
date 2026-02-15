package io.specmatic.core

const val ACCEPT = "Accept"

fun isAcceptHeaderCompatibleWithResponse(requestHeaders: Map<String, String>, responseContentType: String?): Boolean {
    val acceptHeader = requestHeaders.getCaseInsensitive(ACCEPT)?.value?.trim().orEmpty()
    if (acceptHeader.isBlank()) return true

    val responseType = responseContentType?.trim().orEmpty()
    if (responseType.isBlank()) return true

    return isResponseContentTypeAccepted(acceptHeader, responseType)
}

fun isResponseContentTypeAccepted(acceptHeader: String, responseContentType: String): Boolean {
    val parsedResponseType = parseMediaTypeToken(responseContentType.substringBefore(";")) ?: return true

    val acceptedMediaTypes = acceptHeader.split(",")
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .mapNotNull(::parseAcceptToken)
        .filter { it.qValue > 0.0 }
        .toList()

    if (acceptedMediaTypes.isEmpty()) return false

    return acceptedMediaTypes.any { token ->
        val mediaType = token.mediaType
        (mediaType.type == "*" && mediaType.subType == "*") ||
                (mediaType.type == parsedResponseType.type && (mediaType.subType == "*" || mediaType.subType == parsedResponseType.subType))
    }
}

fun acceptHeaderMismatchMessage(acceptHeader: String, responseContentType: String): String {
    return "Accept header \"$acceptHeader\" does not allow response Content-Type \"$responseContentType\""
}

private data class MediaTypeToken(val type: String, val subType: String)
private data class ParsedAcceptToken(val mediaType: MediaTypeToken, val qValue: Double)

private fun parseAcceptToken(token: String): ParsedAcceptToken? {
    val parts = token.split(";").map { it.trim() }
    val mediaType = parseMediaTypeToken(parts.firstOrNull().orEmpty()) ?: return null
    val qValue = parts.drop(1)
        .firstOrNull { it.startsWith("q=", ignoreCase = true) }
        ?.substringAfter("=")
        ?.trim()
        ?.toDoubleOrNull()
        ?: 1.0

    return ParsedAcceptToken(mediaType = mediaType, qValue = qValue)
}

private fun parseMediaTypeToken(token: String): MediaTypeToken? {
    val raw = token.trim().lowercase()
    if (raw.isBlank()) return null

    val parts = raw.split("/", limit = 2)
    if (parts.size != 2) return null

    val type = parts[0].trim()
    val subType = parts[1].trim()
    if (type.isBlank() || subType.isBlank()) return null

    return MediaTypeToken(type = type, subType = subType)
}
