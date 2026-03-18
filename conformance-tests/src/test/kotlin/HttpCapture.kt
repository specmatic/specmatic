import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI

data class HttpCapture(
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val statusCode: Int,
    val responseHeaders: Map<String, String>,
    val responseBody: String
) {
    val path: String get() = URI(url).path

    companion object {
        private val mapper = jacksonObjectMapper()
        private val prefixRegex = Regex("""^[^\s]+\s*\|\s*""")

        fun parseLog(logOutput: String): List<HttpCapture> =
            logOutput.lines()
                .map { it.replace(prefixRegex, "") }
                .filter { it.startsWith("[") }
                .mapNotNull { line ->
                    try {
                        val arr = mapper.readValue<List<Any?>>(line)
                        @Suppress("UNCHECKED_CAST")
                        HttpCapture(
                            method = arr[0] as String,
                            url = arr[1] as String,
                            requestHeaders = (arr[2] as Map<String, String>).mapKeys { it.key.lowercase() },
                            requestBody = arr[3] as? String ?: "",
                            statusCode = (arr[4] as Number).toInt(),
                            responseHeaders = (arr[5] as Map<String, String>).mapKeys { it.key.lowercase() },
                            responseBody = arr[6] as? String ?: ""
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
    }
}
