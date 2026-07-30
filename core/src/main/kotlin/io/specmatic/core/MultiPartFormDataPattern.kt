package io.specmatic.core

import io.ktor.http.ContentType
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.BinaryPattern
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.isOptional
import io.specmatic.core.pattern.isPatternToken
import io.specmatic.core.pattern.newPatternsBasedOn
import io.specmatic.core.pattern.withoutOptionality
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.io.File

sealed interface FilenamePattern {
    data object Ignore : FilenamePattern
    data class Match(val pattern: Pattern?) : FilenamePattern
}

data class MultiPartContentPattern(
    val name: String,
    val content: Pattern,
    val contentType: String? = null,
    val contentEncoding: String? = null,
    val filename: FilenamePattern = FilenamePattern.Ignore,
) {
    fun newBasedOn(row: Row, resolver: Resolver): Sequence<MultiPartContentPattern?> {
        row.requestExample?.let { requestExample ->
            val examplePart = requestExample.multiPartFormData.firstOrNull {
                it.name == withoutOptionality(name)
            }
            if (examplePart != null) return sequenceOf(withExample(examplePart))
            if (isOptional(name)) return sequenceOf(null)
        }

        val contentPatterns = newPatternsBasedOn(row, withoutOptionality(name), content, resolver)
            .map { it.value }
            .map { newContent ->
                copy(
                    name = withoutOptionality(name),
                    content = newContent,
                    filename = filename.fromRow(row),
                )
            }

        return when {
            isOptional(name) && !row.containsField(withoutOptionality(name)) -> sequenceOf(null).plus(contentPatterns)
            else -> contentPatterns
        }
    }

    fun generate(resolver: Resolver): MultiPartContentValue {
        val referencedFile = fileReferencedByFilename()
        val generatedContent = referencedFile
            ?.let { BinaryValue(it.readBytes()) }
            ?: resolver.withCyclePrevention(content, content::generate)
        val generatedFilename = when (filename) {
            FilenamePattern.Ignore -> null
            is FilenamePattern.Match -> filename.pattern?.let {
                resolver.withCyclePrevention(it, it::generate).toStringLiteral()
            }
        }

        return MultiPartContentValue(
            name = withoutOptionality(name),
            content = generatedContent,
            specifiedContentType = contentType,
            contentEncoding = contentEncoding,
            filename = generatedFilename,
        )
    }

    fun matches(value: MultiPartContentValue, resolver: Resolver): Result {
        if (withoutOptionality(name) != value.name) {
            return Failure(
                message = "The contract expected a part name to be $name, but got ${value.name}",
                failureReason = FailureReason.PartNameMisMatch,
                ruleViolation = StandardRuleViolation.VALUE_MISMATCH,
            )
        }

        val results = listOf(
            matchesContent(value.content, resolver),
            matchesContentType(value.contentType),
            matchesContentEncoding(value.contentEncoding),
            matchesFilename(value.filename, resolver),
        )

        return Result.fromResults(results)
    }

    fun nonOptional(): MultiPartContentPattern = copy(name = withoutOptionality(name))

    fun withExample(value: MultiPartContentValue): MultiPartContentPattern {
        val exampleUsesFilenamePattern = value.filename?.let(::isPatternToken) == true
        val loadedValue = value.loadExternalFileContent()
        val exampleContent = when {
            exampleUsesFilenamePattern -> content
            else -> loadedValue.content.exactMatchElseType()
        }

        return copy(
            name = withoutOptionality(name),
            content = exampleContent,
            contentType = loadedValue.contentType ?: contentType,
            contentEncoding = loadedValue.contentEncoding ?: contentEncoding,
            filename = FilenamePattern.Match(loadedValue.filename?.let(::filenamePattern)),
        )
    }

    private fun matchesContent(value: Value, resolver: Resolver): Result {
        val expectedContent = fileReferencedByFilename()
            ?.let { ExactValuePattern(BinaryValue(it.readBytes())) }
            ?: content

        val parsedValue = when (value) {
            is StringValue -> parseContent(expectedContent, value.string, resolver)
            is BinaryValue -> {
                if (expectedContent is ExactValuePattern && expectedContent.pattern is BinaryValue) value
                else parseContent(expectedContent, value.byteArray.decodeToString(), resolver)
            }
            else -> value
        }

        return try {
            resolver.matchesPattern(expectedContent, parsedValue)
        } catch (exception: ContractException) {
            Failure(
                message = exception.report(),
                breadCrumb = "content",
                ruleViolation = StandardRuleViolation.TYPE_MISMATCH,
            )
        } catch (_: Throwable) {
            Failure(
                message = "Expected a ${content.typeName} but got ${value.toStringLiteral()}",
                breadCrumb = "content",
                ruleViolation = StandardRuleViolation.TYPE_MISMATCH,
            )
        }
    }

    private fun parseContent(expectedContent: Pattern, value: String, resolver: Resolver): Value =
        try {
            expectedContent.parse(value, resolver)
        } catch (_: Throwable) {
            StringValue(value)
        }

    private fun matchesContentType(actual: String?): Result {
        val expected = contentType ?: return Success()
        val effectiveActual = actual ?: ContentType.Text.Plain.toString()
        val actualContentType = runCatching { ContentType.parse(effectiveActual) }.getOrNull()
            ?: return contentTypeFailure(expected, effectiveActual)

        val matches = expected.split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { runCatching { ContentType.parse(it) }.getOrNull() }
            .any { expectedContentType -> actualContentType.match(expectedContentType) }

        return if (matches) Success() else contentTypeFailure(expected, effectiveActual)
    }

    private fun contentTypeFailure(expected: String, actual: String): Failure =
        Failure(
            message = "The contract expected content type $expected, but got $actual.",
            breadCrumb = "contentType",
            ruleViolation = StandardRuleViolation.VALUE_MISMATCH,
        )

    private fun matchesContentEncoding(actual: String?): Result {
        val expected = contentEncoding ?: return Success()
        return if (expected == actual) {
            Success()
        } else {
            Failure(
                message = "The contract expected content encoding $expected, but got ${actual ?: "no content encoding"}.",
                breadCrumb = "contentEncoding",
                ruleViolation = StandardRuleViolation.VALUE_MISMATCH,
            )
        }
    }

    private fun matchesFilename(actual: String?, resolver: Resolver): Result =
        when (filename) {
            FilenamePattern.Ignore -> Success()
            is FilenamePattern.Match -> when {
                filename.pattern == null && actual == null -> Success()
                filename.pattern == null -> Failure(
                    message = "The example expected no filename, but got $actual.",
                    breadCrumb = "filename",
                    ruleViolation = StandardRuleViolation.VALUE_MISMATCH,
                )
                actual == null -> Failure(
                    message = "The example expected a filename, but none was received.",
                    breadCrumb = "filename",
                    ruleViolation = StandardRuleViolation.VALUE_MISMATCH,
                )
                else -> resolver.matchesPattern(filenamePatternForMatching(filename.pattern), StringValue(actual))
                    .breadCrumb("filename")
            }
        }

    private fun filenamePatternForMatching(pattern: Pattern): Pattern {
        val exactFilename = pattern as? ExactValuePattern ?: return pattern
        val filename = (exactFilename.pattern as? StringValue)?.string ?: return pattern
        return ExactValuePattern(StringValue(File(filename).name))
    }

    private fun fileReferencedByFilename(): File? {
        if (content !is BinaryPattern) return null
        val match = filename as? FilenamePattern.Match ?: return null
        val exactFilename = match.pattern as? ExactValuePattern ?: return null
        val path = (exactFilename.pattern as? StringValue)?.string ?: return null
        return File(path).takeIf(File::isAbsolute)
    }

    private fun FilenamePattern.fromRow(row: Row): FilenamePattern {
        if (this !is FilenamePattern.Match || pattern == null) return this

        val rowKey = "${withoutOptionality(name)}_filename"
        val filenameFromRow = row.getFieldOrNull(rowKey) ?: return this
        if (filenameFromRow.isBlank()) return FilenamePattern.Match(null)

        val file = File(filenameFromRow.removePrefix("@"))
        return FilenamePattern.Match(
            ExactValuePattern(StringValue(file.name))
        )
    }

    private fun filenamePattern(filename: String): Pattern =
        when {
            filename == "(string)" -> StringPattern()
            else -> ExactValuePattern(StringValue(filename))
        }
}

typealias MultiPartFormDataPattern = MultiPartContentPattern
