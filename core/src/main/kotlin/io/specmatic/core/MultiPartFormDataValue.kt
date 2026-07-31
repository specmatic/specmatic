package io.specmatic.core

import io.ktor.client.request.forms.FormBuilder
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.streams.asInput
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.isPatternToken
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.io.File

const val CONTENT_DISPOSITION = "Content-Disposition"

data class MultiPartContentValue(
    val name: String,
    val content: Value,
    val boundary: String = "#####",
    val specifiedContentType: String? = null,
    val contentEncoding: String? = null,
    val filename: String? = null,
) {
    constructor(
        name: String,
        filename: String,
        contentType: String? = null,
        contentEncoding: String? = null,
        content: MultiPartContent = MultiPartContent(),
        boundary: String = "#####",
    ) : this(
        name = name,
        content = BinaryValue(content.bytes),
        boundary = boundary,
        specifiedContentType = contentType,
        contentEncoding = contentEncoding,
        filename = filename.removePrefix("@"),
    )

    val contentType: String?
        get() = specifiedContentType

    fun inferType(): MultiPartContentPattern =
        MultiPartContentPattern(
            name = name,
            content = content.exactMatchElseType(),
            contentType = contentType,
            contentEncoding = contentEncoding,
            filename = FilenamePattern.Match(filename?.let {
                if (it == "(string)") StringPattern()
                else ExactValuePattern(StringValue(it))
            }),
        )

    fun toDisplayableValue(): String {
        val headers = buildMap {
            put(CONTENT_DISPOSITION, buildString {
                append("""form-data; name="$name"""")
                filename?.let { append("""; filename="${File(it).name}"""") }
            })
            contentType?.let { put(HttpHeaders.ContentType, it) }
            contentEncoding?.let { put(HttpHeaders.ContentEncoding, it) }
        }

        val displayedContent = when (content) {
            is BinaryValue -> "(Binary content not shown)"
            else -> content.toStringLiteral()
        }

        return """
--$boundary
${headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }}

$displayedContent
""".trim()
    }

    fun toJSONObject(): JSONObjectValue {
        val part = mutableMapOf<String, Value>("name" to StringValue(name))
        when {
            filename != null -> part["filename"] = StringValue("@$filename")
            else -> part["content"] = content
        }
        contentType?.let { part["contentType"] = StringValue(it) }
        contentEncoding?.let { part["contentEncoding"] = StringValue(it) }
        return JSONObjectValue(part)
    }

    fun addTo(formBuilder: FormBuilder) {
        val bytes = when (content) {
            is BinaryValue -> content.byteArray
            else -> content.toStringLiteral().encodeToByteArray()
        }

        formBuilder.appendInput(
            key = name,
            headers = Headers.build {
                contentType?.let { append(HttpHeaders.ContentType, ContentType.parse(it).toString()) }
                contentEncoding?.let { append(HttpHeaders.ContentEncoding, it) }
                filename?.let {
                    append(CONTENT_DISPOSITION, "filename=${File(it).name}")
                    append("Content-Transfer-Encoding", "binary")
                }
            },
            size = bytes.size.toLong(),
        ) {
            bytes.inputStream().asInput()
        }
    }

    fun loadExternalFileContent(): MultiPartContentValue {
        val fileNameOrPattern = filename ?: return this
        if (isPatternToken(fileNameOrPattern)) return this

        val file = File(fileNameOrPattern)
        val loadedContent = if (file.exists()) {
            BinaryValue(file.readBytes())
        } else {
            BinaryValue(StringPattern().generate(Resolver()).toStringLiteral().encodeToByteArray())
        }

        return copy(content = loadedContent)
    }

}

data class MultiPartContent(val bytes: ByteArray) {
    constructor(text: String) : this(text.encodeToByteArray())
    constructor(file: File) : this(file.readBytes())
    constructor() : this(ByteArray(0))
}

typealias MultiPartFormDataValue = MultiPartContentValue
typealias MultiPartFileValue = MultiPartContentValue
