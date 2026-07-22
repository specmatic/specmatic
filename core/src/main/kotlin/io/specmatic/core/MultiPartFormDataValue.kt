package io.specmatic.core

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.isPatternToken
import io.specmatic.core.pattern.parsedPattern
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.streams.*
import java.io.File
import kotlin.text.String

const val CONTENT_DISPOSITION = "Content-Disposition"

sealed class MultiPartFormDataValue(open val name: String, open val contentType: String?) {
    abstract fun inferType(): MultiPartFormDataPattern
    abstract fun toDisplayableValue(): String
    abstract fun toJSONObject(): JSONObjectValue
    abstract fun addTo(formBuilder: FormBuilder)
    abstract fun toClauseData(
        clauses: List<GherkinClause>,
        newTypes: Map<String, Pattern>,
        examples: ExampleDeclarations
    ): Triple<List<GherkinClause>, Map<String, Pattern>, ExampleDeclarations>
}

data class MultiPartContentValue(override val name: String, val content: Value, val boundary: String = "#####", val specifiedContentType: String? = null) : MultiPartFormDataValue(name,
    specifiedContentType ?: content.httpContentType
) {
    override val contentType: String
      get() {
          return specifiedContentType ?: content.httpContentType
      }

    override fun inferType(): MultiPartFormDataPattern {
        return MultiPartContentPattern(name, content.exactMatchElseType(), contentType)
    }

    override fun toDisplayableValue(): String = """
--$boundary
$CONTENT_DISPOSITION: form-data; name="$name"
Content-Type: $contentType

$content
""".trim()

    override fun toJSONObject(): JSONObjectValue =
            JSONObjectValue(mapOf("name" to StringValue(name), "content" to StringValue(content.toStringLiteral()), "contentType" to StringValue(contentType)))

    override fun addTo(formBuilder: FormBuilder) {
        formBuilder.append(name, content.toStringLiteral(), Headers.build {
            append(HttpHeaders.ContentType, ContentType.parse(contentType))
        })
    }

    override fun toClauseData(
        clauses: List<GherkinClause>,
        newTypes: Map<String, Pattern>,
        examples: ExampleDeclarations
    ): Triple<List<GherkinClause>, Map<String, Pattern>, ExampleDeclarations> {
        val (typeDeclaration, newExamples) = this.content.typeDeclarationWithKey(this.name, newTypes, examples)

        val newGherkinClause = GherkinClause(
            "request-part ${this.name} ${typeDeclaration.typeValue}",
            GherkinSection.When
        )
        return Triple(clauses.plus(newGherkinClause), typeDeclaration.types, examples.plus(newExamples))
    }
}

data class MultiPartContent(val bytes: ByteArray) {
    constructor(text: String): this(text.encodeToByteArray())
    constructor(file: File): this(file.readBytes())
    constructor(): this(ByteArray(0))

    val length: Long = bytes.size.toLong()
    val text: String = String(bytes)
    val input: Input = bytes.inputStream().asInput()

    fun toStringLiteral(): String {
        return String(bytes)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MultiPartContent

        if (!bytes.contentEquals(other.bytes)) return false
        if (length != other.length) return false
        if (text != other.text) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + length.hashCode()
        result = 31 * result + text.hashCode()
        return result
    }
}

data class MultiPartFileValue(override val name: String, val filename: String, override val contentType: String? = null, val contentEncoding: String? = null, val content: MultiPartContent = MultiPartContent(), val boundary: String = "#####") : MultiPartFormDataValue(name, contentType) {
    override fun inferType(): MultiPartFormDataPattern {
        return MultiPartFilePattern(
            name = name,
            filename = filenameToType(),
            contentType = contentType,
            contentEncoding = contentEncoding,
            content = content.takeIf { it.length > 0 }?.let { ExactValuePattern(BinaryValue(it.bytes)) }
        )
    }

    private fun filenameToType(): Pattern? =
        filename.takeIf { it.isNotBlank() }?.let { parsedPattern(it.removePrefix("@")) }

    override fun toDisplayableValue(): String {
        val headers = mapOf (
                CONTENT_DISPOSITION to listOfNotNull(
                    """form-data; name="$name"""",
                    filename.takeIf { it.isNotBlank() }?.let { """filename="$it""" }
                ).joinToString("; "),
                "Content-Type" to (contentType ?: ""),
                "Content-Encoding" to (contentEncoding ?: "")
        ).filter { it.value.isNotBlank() }

        val headerString = headers.entries.joinToString("\n") {
            "${it.key}: ${it.value}"
        }

        return """
--$boundary
$headerString

${filename.takeIf(String::isNotBlank)?.let { "(File content not shown here.  Please examine the file ${it.removePrefix("@")})" } ?: "(File content not shown here.)"}
""".trim()
    }

    override fun toJSONObject() =
            JSONObjectValue(mapOf("name" to StringValue(name), "filename" to StringValue("@${filename}")).let { map ->
                when (contentType) {
                    null -> map
                    else -> map.plus("contentType" to StringValue(contentType))
                }
            }.let { map ->
                when (contentEncoding) {
                    null -> map
                    else -> map.plus("contentEncoding" to StringValue(contentEncoding))
                }
            })

    override fun addTo(formBuilder: FormBuilder) {
        formBuilder.appendInput(name, Headers.build {
            if(contentType != null)
                append(HttpHeaders.ContentType, ContentType.parse(contentType))

            if(contentEncoding != null)
                append(HttpHeaders.ContentEncoding, contentEncoding)

            append("Content-Transfer-Encoding", "binary")

            filename.takeIf(String::isNotBlank)?.let {
                val partFilePath = it.removePrefix("@")
                val partFileName = File(partFilePath).name
                append(CONTENT_DISPOSITION, "filename=$partFileName")
            }
        }, content.length) {
            content.input
        }
    }

    override fun toClauseData(clauses: List<GherkinClause>, newTypes: Map<String, Pattern>, examples: ExampleDeclarations ): Triple<List<GherkinClause>, Map<String, Pattern>, ExampleDeclarations> {
        val contentEncoding = this.contentType?.let { this.contentEncoding } ?: ""
        val contentType = this.contentType ?: ""

        val (newFilename, newExamples) = when {
            !isPatternToken(filename) -> {
                val filenameExampleName = examples.getNewName("${name}_filename", newTypes.keys)
                val newExamples = examples.plus(filenameExampleName to filename)

                Pair("(string)", newExamples)
            }
            isPatternToken(filename) && filename.trim() != "(string)" -> throw ContractException("Only (string) is supported as a type", name)
            else -> Pair(filename, examples)
        }

        return Triple(
            clauses.plus(
                GherkinClause(
                    "request-part ${this.name} @$newFilename $contentType $contentEncoding".trim(),
                    GherkinSection.When
                )
            ), newTypes, newExamples
        )
    }
}
