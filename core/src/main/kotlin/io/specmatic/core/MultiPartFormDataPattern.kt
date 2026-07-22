package io.specmatic.core

import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.*
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.io.File

sealed class MultiPartFormDataPattern(open val name: String, open val contentType: String?) {
    abstract fun newBasedOn(row: Row, resolver: Resolver): Sequence<MultiPartFormDataPattern?>
    abstract fun generate(resolver: Resolver): MultiPartFormDataValue
    abstract fun matches(value: MultiPartFormDataValue, resolver: Resolver): Result
    abstract fun nonOptional(): MultiPartFormDataPattern
}

data class MultiPartContentPattern(override val name: String, val content: Pattern, override val contentType: String? = null) : MultiPartFormDataPattern(name, contentType) {
    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<MultiPartContentPattern?> =
        newPatternsBasedOn(row, withoutOptionality(name), content, resolver).map { it.value }.map { newContent: Pattern ->
            MultiPartContentPattern(
                withoutOptionality(name),
                newContent,
                contentType
            )
        }.let {
            when {
                isOptional(name) && !row.containsField(withoutOptionality(name)) -> sequenceOf(null).plus(it)
                else -> it
            }
        }

    override fun generate(resolver: Resolver): MultiPartFormDataValue =
            MultiPartContentValue(name, resolver.withCyclePrevention(content, content::generate), specifiedContentType = contentType)

    override fun matches(value: MultiPartFormDataValue, resolver: Resolver): Result {
        if(withoutOptionality(name) != value.name)
            return Failure(
                message = "The contract expected a part name to be $name, but got ${value.name}",
                failureReason = FailureReason.PartNameMisMatch,
                ruleViolation = StandardRuleViolation.VALUE_MISMATCH
            )

//        if(contentType != null && value.contentType != null && contentType != value.contentType)
//            return Failure("Expected $contentType, but got ${value.contentType}")

        return when(value) {
            is MultiPartFileValue -> {
                try {
                    val parsedContent = try { content.parse(value.content.toStringLiteral(), resolver) } catch (e: Throwable) { StringValue(value.content.toStringLiteral()) }
                    resolver.matchesPattern(content, parsedContent)
                } catch (e: ContractException) {
                    Failure(e.report(), breadCrumb = "content", ruleViolation = StandardRuleViolation.TYPE_MISMATCH)
                } catch (e: Throwable) {
                    Failure(
                        message = "Expected a ${content.typeName} but got ${value.content.toStringLiteral()}",
                        breadCrumb = "content",
                        ruleViolation = StandardRuleViolation.TYPE_MISMATCH
                    )
                }
            }
            is MultiPartContentValue -> {
                if(value.content is StringValue) {
                    return try {
                        val parsedContent = try { content.parse(value.content.toStringLiteral(), resolver) } catch (e: Throwable) { StringValue(value.content.toStringLiteral()) }
                        resolver.matchesPattern(content, parsedContent)
                    } catch (e: ContractException) {
                        Failure(e.report(), breadCrumb = "content", ruleViolation = StandardRuleViolation.TYPE_MISMATCH)
                    } catch (e: Throwable) {
                        Failure(
                            message = "Expected a ${content.typeName} but got ${value.content.toStringLiteral()}",
                            breadCrumb = "content",
                            ruleViolation = StandardRuleViolation.TYPE_MISMATCH
                        )
                    }
                } else {
                    content.matches(value.content, resolver)
                }
            }
        }
    }

    override fun nonOptional(): MultiPartFormDataPattern {
        return copy(name = withoutOptionality(name))
    }
}

data class MultiPartFilePattern(
    override val name: String,
    val filename: Pattern?,
    override val contentType: String? = null,
    val contentEncoding: String? = null,
    val content: Pattern? = null
) : MultiPartFormDataPattern(name, contentType) {
    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<MultiPartFormDataPattern?> {
        val rowKey = "${name}_filename"
        return sequenceOf(this.copy(filename = if(row.containsField(rowKey)) ExactValuePattern(StringValue(row.getField(rowKey))) else filename))
    }

    override fun generate(resolver: Resolver): MultiPartFormDataValue {
        val generatedFilename = filename?.let {
            resolver.withCyclePrevention(it, it::generate).toStringLiteral()
        }.orEmpty()
        val generatedContent = content?.let {
            resolver.withCyclePrevention(it, it::generate).toMultiPartContent()
        } ?: MultiPartContent()

        return MultiPartFileValue(
            name = name,
            filename = generatedFilename,
            contentType = contentType ?: "",
            contentEncoding = contentEncoding,
            content = generatedContent
        )
    }

    override fun matches(value: MultiPartFormDataValue, resolver: Resolver): Result {
        return when {
            value !is MultiPartFileValue -> Failure("The contract expected a file, but got content instead.", ruleViolation = StandardRuleViolation.TYPE_MISMATCH)
            name != value.name -> Failure("The contract expected a part name to be $name, but got ${value.name}.", failureReason = FailureReason.PartNameMisMatch, ruleViolation = StandardRuleViolation.VALUE_MISMATCH)
            content == null && fileContentMismatch(value, resolver) -> fileContentMismatchError(value, resolver)
            content != null && filenameMismatch(value, resolver) -> filenameMismatchError(value, resolver)
            content != null && contentMismatch(value, resolver) -> contentMismatchError(value, resolver)
            //TODO: Fix below comment
//            contentType != null && value.contentType != null && value.contentType != contentType -> Failure("The contract expected ${contentType.let { "content type $contentType" }}, but got ${value.contentType?.let { "content type $value.contentType" } ?: "no content type."}.")
            contentEncoding != null && value.contentEncoding != contentEncoding -> {
                val contentEncodingMessage = contentEncoding.let { "content encoding $contentEncoding" }
                val receivedContentEncodingMessage = value.contentEncoding?.let { "content encoding ${value.contentEncoding}" } ?: "no content encoding"
                Failure(
                    message = "The contract expected ${contentEncodingMessage}, but got ${receivedContentEncodingMessage}.",
                    breadCrumb = "contentEncoding",
                    ruleViolation = StandardRuleViolation.VALUE_MISMATCH
                )
            }
            else -> Success()
        }
    }

    private fun filenameMismatch(value: MultiPartFileValue, resolver: Resolver): Boolean =
        filename?.matches(StringValue(value.filename), resolver)?.isSuccess()?.not() ?: false

    private fun filenameMismatchError(value: MultiPartFileValue, resolver: Resolver): Failure =
        Failure(
            message = "In the part named $name, the contract expected the filename to be ${filename?.typeName}, but got ${value.filename}.",
            failureReason = FailureReason.PartNameMisMatch,
            cause = filename?.matches(StringValue(value.filename), resolver) as? Failure,
            ruleViolation = StandardRuleViolation.VALUE_MISMATCH
        )

    private fun contentMismatch(value: MultiPartFileValue, resolver: Resolver): Boolean =
        content?.matches(BinaryValue(value.content.bytes), resolver)?.isSuccess()?.not() ?: false

    private fun contentMismatchError(value: MultiPartFileValue, resolver: Resolver): Failure =
        Failure(
            message = "In the part named $name, the file content did not match the contract.",
            breadCrumb = "content",
            cause = content?.matches(BinaryValue(value.content.bytes), resolver) as? Failure,
            ruleViolation = StandardRuleViolation.VALUE_MISMATCH
        )

    private fun fileContentMismatchError(
        value: MultiPartFileValue,
        resolver: Resolver
    ) = when(filename) {
        is ExactValuePattern -> {
            Failure(
                message = "In the part named $name, the contents in request did not match the value in file ${filename.pattern.toStringLiteral()}",
                failureReason = FailureReason.PartNameMisMatch,
                ruleViolation = StandardRuleViolation.VALUE_MISMATCH
            )
        }
        null -> Failure(
            message = "In the part named $name, the contract did not define a filename pattern.",
            failureReason = FailureReason.PartNameMisMatch,
            ruleViolation = StandardRuleViolation.VALUE_MISMATCH
        )
        else -> Failure(
            message = "In the part named $name, the contract expected the filename to be ${filename.typeName}, but got ${value.filename}.",
            failureReason = FailureReason.PartNameMisMatch,
            cause = filename.matches(StringValue(value.filename), resolver) as Failure,
            ruleViolation = StandardRuleViolation.VALUE_MISMATCH
        )
    }

    private fun fileContentMismatch(
        value: MultiPartFileValue,
        resolver: Resolver
    ): Boolean {
        return when(filename) {
            is ExactValuePattern -> {
                val patternFilePath = filename.pattern.toStringLiteral()
                val bytes = File(patternFilePath).canonicalFile.also {
                    if(!it.exists()) {
                        println(it.canonicalFile.path + " does not exist")
                        throw Exception(it.canonicalFile.path + " does not exist")
                    }
                }.readBytes()
                val contentBytes = value.content.bytes
                !bytes.contentEquals(contentBytes)
            }
            null -> false
            else -> !filename.matches(StringValue(value.filename), resolver).isSuccess()
        }
    }

    override fun nonOptional(): MultiPartFormDataPattern {
        return copy(name = withoutOptionality(name))
    }
}

private fun Value.toMultiPartContent(): MultiPartContent =
    when (this) {
        is BinaryValue -> MultiPartContent(byteArray)
        else -> MultiPartContent(toStringLiteral())
    }
