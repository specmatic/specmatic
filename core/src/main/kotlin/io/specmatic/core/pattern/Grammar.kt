package io.specmatic.core.pattern

import io.specmatic.core.DefaultMismatchMessages
import io.specmatic.core.MismatchMessages
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.*
import io.specmatic.core.value.*
import java.io.File

const val XML_ATTR_OPTIONAL_SUFFIX = ".opt"
const val DEFAULT_OPTIONAL_SUFFIX = "?"
const val UTF_BYTE_ORDER_MARK = "\uFEFF"

fun withoutOptionality(key: String): String {
    return when {
        key.endsWith(DEFAULT_OPTIONAL_SUFFIX) -> key.removeSuffix(DEFAULT_OPTIONAL_SUFFIX)
        key.endsWith(XML_ATTR_OPTIONAL_SUFFIX) -> key.removeSuffix(XML_ATTR_OPTIONAL_SUFFIX)
        else -> key
    }
}

internal fun withOptionality(key: String): String {
    return when {
        key.endsWith(DEFAULT_OPTIONAL_SUFFIX) -> key
        else -> "$key$DEFAULT_OPTIONAL_SUFFIX"
    }
}

fun isOptional(key: String): Boolean =
    key.endsWith(DEFAULT_OPTIONAL_SUFFIX) || key.endsWith(XML_ATTR_OPTIONAL_SUFFIX)

internal fun containsKey(jsonObject: Map<String, Any?>, key: String) =
    when {
        isOptional(key) -> withoutOptionality(key) in jsonObject
        else -> key in jsonObject
    }

internal val builtInPatterns = mapOf(
    "(number)" to NumberPattern(),
    "(integer)" to NumberPattern(),
    "(string)" to StringPattern(),
    "(email)" to EmailPattern(),
    "(boolean)" to BooleanPattern(),
    "(null)" to NullPattern,
    "(empty)" to EmptyStringPattern,
    "(date)" to DatePattern(),
    "(datetime)" to DateTimePattern(),
    "(time)" to TimePattern(),
    "(uuid)" to UUIDPattern(),
    "(url)" to URLPattern(URLScheme.EITHER),
    "(url-http)" to URLPattern(URLScheme.HTTP),
    "(url-https)" to URLPattern(URLScheme.HTTPS),
    "(url-path)" to URLPattern(URLScheme.PATH),
    "(anything)" to AnythingPattern,
    "(anyvalue)" to AnyValuePattern
)

fun isBuiltInPattern(pattern: Any): Boolean =
    when (pattern) {
        is String -> when {
            pattern in builtInPatterns -> true
            isPatternToken(pattern) -> when {
                isLookupRowPattern(pattern) || " in " in pattern || isDictionaryPattern(pattern) -> true
                else -> false
            }

            else -> false
        }

        else -> false
    }

fun isDictionaryPattern(pattern: String): Boolean {
    val pieces = withoutPatternDelimiters(pattern).trim().split("\\s+".toRegex())

    return when (pieces[0]) {
        "dictionary" -> pieces.size == 3
        else -> false
    }
}

fun isStringPatternWithRestrictions(patternValue: String): Boolean {
    val tokens = patternValue.split(" ")
    return tokens[0] == "(string)" && listOf("minLength", "maxLength").any { it in tokens }
}

fun isNumberPatternWithRestrictions(patternValue: String): Boolean {
    val tokens = patternValue.split(" ")
    return tokens[0] == "(number)" && listOf("minLength", "maxLength").any { it in tokens }
}

fun isPatternToken(patternValue: Any?) =
    when (patternValue) {
        is String -> patternValue.startsWith("(") && patternValue.endsWith(")")
        is StringValue -> patternValue.string.startsWith("(") && patternValue.string.endsWith(")")
        else -> false
    }

internal fun getBuiltInPattern(patternString: String): Pattern =
    when {
        isPatternToken(patternString) -> builtInPatterns.getOrElse(patternString) {
            when {
                isDictionaryPattern(patternString) -> {
                    val pieces = breakIntoParts(
                        withoutPatternDelimiters(patternString),
                        "\\s+".toRegex(),
                        3,
                        "Dictionary type must have 3 parts: type name, key and value"
                    )

                    val patterns = pieces.slice(1..2).map { parsedPattern(withPatternDelimiters(it.trim())) }
                    DictionaryPattern(patterns[0], patterns[1])
                }

                isLookupRowPattern(patternString) -> {
                    val patternParts = breakIntoParts(
                        withoutPatternDelimiters(patternString),
                        ":",
                        2,
                        "Type with key must have the key before the colon and the type specification after it. Got $patternString"
                    )

                    val (key, patternSpec) = patternParts
                    val pattern = parsedPattern(withPatternDelimiters(patternSpec))

                    LookupRowPattern(pattern, key)
                }

                patternString.contains(" in ") -> {
                    val patternParts = breakIntoParts(
                        withoutPatternDelimiters(patternString),
                        " in ",
                        2,
                        "$patternString seems incomplete"
                    )

                    if (patternParts[1] != "string")
                        throw ContractException("""Types can only be declared to be "in string", you probably meant (${patternParts[1]} in string)""")

                    PatternInStringPattern(parsedPattern(withPatternDelimiters(patternParts[0])))
                }

                else -> throw ContractException("Type $patternString does not exist.")
            }
        }

        else -> throw ContractException("Type $patternString is not a type specifier.")
    }

fun breakIntoParts(text: String, delimiter: Regex, count: Int, errorMessage: String): List<String> {
    val pieces = text.split(delimiter)
    if (pieces.size != count)
        throw ContractException(errorMessage)
    return pieces
}

fun breakIntoParts(text: String, delimiter: String, count: Int, errorMessage: String): List<String> {
    val pieces = text.split(delimiter)
    if (pieces.size != count)
        throw ContractException(errorMessage)
    return pieces
}

fun withoutPatternDelimiters(patternValue: String) = patternValue.removeSurrounding("(", ")")
fun withPatternDelimiters(name: String): String = "($name)"

fun withoutListToken(patternValue: Any): String {
    val patternString = (patternValue as String).trim()
    return "(" + withoutPatternDelimiters(patternString).removeSuffix("*") + ")"
}

fun isRepeatingPattern(patternValue: Any?): Boolean =
    patternValue != null && isPatternToken(patternValue) && (patternValue as String).endsWith("*)")

fun stringToPattern(patternValue: String, key: String?): Pattern =
    when {
        isPatternToken(patternValue) -> DeferredPattern(patternValue, key)
        else -> ExactValuePattern(StringValue(patternValue))
    }

fun parsedPattern(rawContent: String, key: String? = null, typeAlias: String? = null): Pattern {
    return rawContent.trim().removePrefix(UTF_BYTE_ORDER_MARK).let {
        when {
            isPatternToken(it) && it.contains("/") -> {
                val (container, type) = withoutPatternDelimiters(it).split("/")
                if (container != "csv")
                    throw ContractException("$container is not supported")

                val typeString = "($type)"
                val innerPattern = builtInPatterns[typeString] ?: DeferredPattern(typeString)
                CsvPattern(innerPattern)
            }

            it.isEmpty() -> EmptyStringPattern
            it.startsWith("{") -> toJSONObjectPattern(it, typeAlias = typeAlias)
            it.startsWith("[") -> JSONArrayPattern(it, typeAlias = typeAlias)
            it.startsWith("<") -> XMLPattern(it, typeAlias = typeAlias)
            isStringPatternWithRestrictions(it) -> {
                val tokens = it.split(" ")

                val restrictions =
                    tokens.drop(1).chunked(2).associate { restriction -> restriction[0] to restriction[1] }
                try {
                    StringPattern(
                        typeAlias = typeAlias,
                        minLength = restrictions["minLength"]?.toIntOrNull(),
                        maxLength = restrictions["maxLength"]?.toIntOrNull()
                    )
                } catch (e: IllegalArgumentException) {
                    throw ContractException(e.message ?: "", exceptionCause = e)
                }
            }

            isNumberPatternWithRestrictions(it) -> {
                val tokens = it.split(" ")

                val restrictions =
                    tokens.drop(1).chunked(2).associate { restriction -> restriction[0] to restriction[1] }
                try {
                    NumberPattern(
                        typeAlias = typeAlias,
                        minLength = restrictions["minLength"]?.toInt() ?: 1,
                        maxLength = restrictions["maxLength"]?.toInt() ?: Int.MAX_VALUE
                    )
                } catch (e: IllegalArgumentException) {
                    throw ContractException(e.message ?: "", exceptionCause = e)
                }
            }

            isPatternToken(it) -> when {
                isDictionaryPattern(it) -> getBuiltInPattern(it)
                isLookupRowPattern(it) -> {
                    val (pattern, lookupKey) = parseLookupRowPattern(it)
                    LookupRowPattern(parsedPattern(pattern, typeAlias = typeAlias), lookupKey)
                }

                isOptionalValuePattern(it) -> {
                    val patterns = listOf(
                        DeferredPattern("(empty)", key),
                        parsedPattern(withoutNullToken(it), typeAlias = typeAlias)
                    )
                    AnyPattern(patterns, extensions = patterns.extractCombinedExtensions())
                }

                isRestPattern(it) -> RestPattern(parsedPattern(withoutRestToken(it), typeAlias = typeAlias))
                isRepeatingPattern(it) -> ListPattern(parsedPattern(withoutListToken(it), typeAlias = typeAlias))
                it == "(number)" || it == "(integer)" -> DeferredPattern(it, null)
                isBuiltInPattern(it) -> getBuiltInPattern(it)
                else -> DeferredPattern(it, key)
            }

            else -> ExactValuePattern(StringValue(it))
        }
    }
}

fun parseLookupRowPattern(token: String): Pair<String, String> {
    val parts = withoutPatternDelimiters(token).split(":".toRegex(), 2).map { it.trim() }

    val key = parts.first()
    val patternToken = parts[1]

    return Pair(withPatternDelimiters(patternToken), key)
}

fun isLookupRowPattern(token: String): Boolean {
    val parts = withoutPatternDelimiters(token).split(":".toRegex())

    return when (parts.size) {
        2 -> true
        else -> false
    }
}

fun parsedJSON(content: String, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Value {
    return content.removePrefix(UTF_BYTE_ORDER_MARK).trim().let {
        when {
            it.startsWith("{") -> try {
                JSONObjectValue(jsonStringToValueMap(it))
            } catch (e: Throwable) {
                throw ContractException(
                    "Could not parse json object, got error: ${e.localizedMessage ?: e.message}",
                    exceptionCause = e
                )
            }

            it.startsWith("[") -> try {
                JSONArrayValue(jsonStringToValueArray(it))
            } catch (e: Throwable) {
                throw ContractException(
                    "Could not parse json array, got error: ${e.localizedMessage ?: e.message}",
                    exceptionCause = e
                )
            }

            else -> throw ContractException(
                mismatchMessages.mismatchMessage(
                    "json value",
                    stringInErrorMessage(content)
                )
            )
        }
    }
}

fun stringInErrorMessage(value: String): String {
    return value.ifBlank {
        "an empty string or no body value"
    }
}

fun parsedJSONObject(content: String, mismatchMessages: MismatchMessages = DefaultMismatchMessages): JSONObjectValue {
    return content.trim().removePrefix(UTF_BYTE_ORDER_MARK).let {
        when {
            it.startsWith("{") -> try {
                JSONObjectValue(jsonStringToValueMap(it))
            } catch (e: Throwable) {
                throw ContractException(
                    "Could not parse json object, got error: ${e.localizedMessage ?: e.message}",
                    exceptionCause = e
                )
            }

            else -> throw ContractException(
                mismatchMessages.mismatchMessage(
                    "json object",
                    stringInErrorMessage(content)
                )
            )
        }
    }
}

fun parsedJSONArray(content: String, mismatchMessages: MismatchMessages = DefaultMismatchMessages): JSONArrayValue {
    return content.trim().removePrefix(UTF_BYTE_ORDER_MARK).let {
        when {
            it.startsWith("[") -> try {
                JSONArrayValue(jsonStringToValueArray(it))
            } catch (e: Throwable) {
                throw ContractException(
                    "Could not parse json array, got error: ${e.localizedMessage ?: e.message}",
                    exceptionCause = e
                )
            }

            else -> throw ContractException(
                mismatchMessages.mismatchMessage(
                    "json array",
                    stringInErrorMessage(content)
                )
            )
        }
    }
}

fun parsedValue(content: String?): Value {
    return content?.trim()?.removePrefix(UTF_BYTE_ORDER_MARK)?.let {
        try {
            when {
                it.startsWith("{") -> JSONObjectValue(jsonStringToValueMap(it))
                it.startsWith("[") -> JSONArrayValue(jsonStringToValueArray(it))
                it.startsWith("<") -> toXMLNode(it)
                else -> StringValue(it)
            }
        } catch (e: Throwable) {
            StringValue(it)
        }
    } ?: EmptyString
}

fun parsedScalarValue(content: String?): Value {
    val trimmed = content?.trim()?.removePrefix(UTF_BYTE_ORDER_MARK) ?: return NullValue
    return when {
        trimmed.toIntOrNull() != null -> NumberValue(trimmed.toInt())
        trimmed.toLongOrNull() != null -> NumberValue(trimmed.toLong())
        trimmed.toDoubleOrNull() != null -> NumberValue(trimmed.toDouble())
        trimmed.lowercase() in setOf("true", "false") -> BooleanValue(trimmed.toBoolean())
        else -> StringValue(trimmed)
    }
}

fun readValue(file: File): Value = processContent(file.readText(), file.extension)

inline fun <reified T : Value> readValueAs(file: File): T {
    return when (val parsedValue = readValue(file)) {
        is T -> parsedValue
        else -> throw ClassCastException("Expected ${T::class.simpleName} but got ${parsedValue::class.simpleName}")
    }
}

private fun processContent(content: String?, extension: String): Value {
    val trimmedContent = content?.trim()?.removePrefix(UTF_BYTE_ORDER_MARK) ?: return EmptyString

    return runCatching {
        when(extension.lowercase()) {
            "json" -> parsedJSON(trimmedContent)
            "yaml", "yml" -> yamlStringToValue(trimmedContent)
            "xml" -> toXMLNode(trimmedContent)
            else -> parsedScalarValue(trimmedContent)
        }
    }.getOrElse { e ->
        logger.debug(e)
        StringValue(trimmedContent)
    }
}
