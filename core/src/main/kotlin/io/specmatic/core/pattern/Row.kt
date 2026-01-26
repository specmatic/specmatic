package io.specmatic.core.pattern

import io.specmatic.core.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONComposite
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import kotlin.collections.orEmpty

const val DEREFERENCE_PREFIX = "$"
const val FILENAME_PREFIX = "@"
const val REQUEST_BODY_FIELD = "(REQUEST-BODY)"

data class Row(
    val exampleFields: Map<String, String> = emptyMap(),
    val variables: Map<String, String> = emptyMap(),
    val references: Map<String, References> = emptyMap(),
    val name: String = "",
    val fileSource: String? = null,
    val responseExampleForAssertion: HttpResponse? = null,
    val exactResponseExample: ResponseExample? = null,
    val requestExample: HttpRequest? = null,
    val responseExample: HttpResponse? = null,
    val isPartial: Boolean = false,
    val enableBodyFieldsLookup: Boolean = false
) {

    private val jsonBodyField = jsonBodyFieldOrNull()

    fun withBodyFieldsLookupEnabled() = this.copy(enableBodyFieldsLookup = true)

    fun getField(exampleFieldName: String): String {
        return getValue(exampleFieldName).fetch()
    }

    fun getFieldOrNull(exampleFieldName: String): String? {
        if (!containsField(exampleFieldName)) return null
        return getField(exampleFieldName)
    }

    private fun getValue(exampleFieldName: String): RowValue {
        val value = if(enableBodyFieldsLookup) {
            jsonBodyField?.getValueFromTopLevelKeys(exampleFieldName)?.toStringLiteral()
                ?: exampleFields.getValue(exampleFieldName)
        } else {
            exampleFields.getValue(exampleFieldName)
        }

        return rowValueWith(value)
    }

    private fun rowValueWith(value: String): RowValue {
        return when {
            isContextValue(value) && isReferenceValue(value) -> ReferenceValue(ValueReference(value), references)
            isContextValue(value) -> VariableValue(ValueReference(value), variables)
            isFileValue(value) -> FileValue(withoutPatternDelimiters(value).removePrefix("@"))
            else -> SimpleValue(value)
        }
    }

    private fun isFileValue(value: String): Boolean = isPatternToken(value) && withoutPatternDelimiters(value).startsWith(FILENAME_PREFIX)

    private fun isReferenceValue(value: String): Boolean = value.contains(".")

    private fun isContextValue(value: String): Boolean =
        isPatternToken(value) && withoutPatternDelimiters(value).trim().startsWith(DEREFERENCE_PREFIX)

    fun containsField(exampleFieldName: String): Boolean {
        return if (jsonBodyField?.hasScalarValueForKey(exampleFieldName) == true) true
        else exampleFields.containsKey(exampleFieldName)
    }

    fun withoutOmittedKeys(
        keys: Map<String, Pattern>,
        defaultExampleResolver: DefaultExampleResolver,
    ): Map<String, Pattern> {
        if (this.hasNoRequestExamples() && this.fileSource == null) {
            return keys
        }

        return keys.filter { (key, pattern) ->
            keyIsMandatory(key) || keyHasExample(withoutOptionality(key), pattern, defaultExampleResolver)
        }
    }

    private fun hasNoRequestExamples(): Boolean {
        return exampleFields.isEmpty() && jsonBodyField == null
    }

    private fun keyHasExample(
        key: String,
        pattern: Pattern,
        defaultExampleResolver: DefaultExampleResolver,
    ): Boolean = this.containsField(key) || defaultExampleResolver.hasExample(pattern)

    private fun keyIsMandatory(key: String): Boolean = !isOptional(key)

    fun stepDownOneLevelInJSONHierarchy(key: String): Row {
        val jsonBodyField = this.jsonBodyField ?: return this

        if (jsonBodyField !is JSONObjectValue) {
            throw ContractException("Example provided is a JSON array, which can't contain key $key")
        }

        val value = jsonBodyField.findFirstChildByPath(withoutOptionality(key)) ?: return withNoJSONObjectExample()

        if (value !is JSONComposite) {
            return withNoJSONObjectExample()
        }

        return this.copy(exampleFields = updateBodyFieldInExampleFields(value))
    }

    private fun updateBodyFieldInExampleFields(value: Value) = exampleFields.plus(REQUEST_BODY_FIELD to value.toStringLiteral())

    private fun withNoJSONObjectExample(): Row = this.copy(exampleFields = exampleFields - REQUEST_BODY_FIELD)

    fun stepDownIntoList(): Row {
        val jsonBodyField = this.jsonBodyField ?: return this

        if (jsonBodyField !is JSONArrayValue) {
            throw ContractException("The example provided is a JSON object, while the specification expects a list")
        }

        val list = jsonBodyField.list

        val firstValue = list.firstOrNull()
        if (firstValue is JSONComposite) {
            return this.copy(
                exampleFields = updateBodyFieldInExampleFields(firstValue)
            )
        }

        return withNoJSONObjectExample()
    }

    fun isEmpty(): Boolean {
        return exampleFields.isEmpty() && jsonBodyField == null
    }

    fun removeKey(property: String): Row {
        val rowWithoutProperty = copy(exampleFields = exampleFields - property)

        val jsonBodyField = rowWithoutProperty.jsonBodyField?.removeKey(property) ?: return rowWithoutProperty

        return rowWithoutProperty.copy(
            exampleFields = updateBodyFieldInExampleFields(jsonBodyField)
        )
    }

    fun updateRequest(
        request: HttpRequest,
        requestPattern: HttpRequestPattern,
    ): Row {
        val path = requestPattern.httpPathPattern?.extractPathParams(request.path.orEmpty()).orEmpty()
        val headers: Map<String, String> = request.headers
        val queryParams = request.queryParams.asValueMap().mapValues { it.value.toStringLiteral() }
        val bodyEntry =
            if (request.body !is NoBodyValue) {
                mapOf(REQUEST_BODY_FIELD to request.body.toStringLiteral())
            } else {
                emptyMap()
            }

        return this.copy(
            exampleFields = path + headers + queryParams + bodyEntry
        ).copy(requestExample = request)
    }

    private fun jsonBodyFieldOrNull(): JSONComposite? {
        val bodyField = exampleFields[REQUEST_BODY_FIELD] ?: return null
        return runCatching {
            val parsedBodyField = parsedJSON(bodyField)
            parsedBodyField as? JSONComposite
        }.getOrNull()
    }
}
