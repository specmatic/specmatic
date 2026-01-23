package io.specmatic.core.pattern

import io.specmatic.core.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONComposite
import io.specmatic.core.value.JSONObjectValue

const val DEREFERENCE_PREFIX = "$"
const val FILENAME_PREFIX = "@"
const val REQUEST_BODY_FIELD = "(REQUEST-BODY)"

data class Row(
    val exampleFields: Map<String, String> = emptyMap(),
    val variables: Map<String, String> = emptyMap(),
    val references: Map<String, References> = emptyMap(),
    val name: String = "",
    val fileSource: String? = null,
    val requestBodyJSONExample: JSONComposite? = null,
    val responseExampleForAssertion: HttpResponse? = null,
    val exactResponseExample: ResponseExample? = null,
    val requestExample: HttpRequest? = null,
    val responseExample: HttpResponse? = null,
    val isPartial: Boolean = false,
) {

    fun noteRequestBody(): Row {
        if (!this.containsField(REQUEST_BODY_FIELD)) {
            return this
        }

        val requestBody = this.getField(REQUEST_BODY_FIELD).trim()

        return try {
            val parsed = parsedJSON(requestBody)

            if (parsed is JSONComposite) {
                this.copy(requestBodyJSONExample = parsed)
            } else {
                this
            }
        } catch (_: ContractException) {
            this
        }
    }

    fun getField(exampleFieldName: String): String = getValue(exampleFieldName).fetch()

    fun getFieldOrNull(exampleFieldName: String): String? {
        if (!containsField(exampleFieldName)) return null
        return getField(exampleFieldName)
    }

    private fun getValue(exampleFieldName: String): RowValue {
        val value = requestBodyJSONExample?.getValueFromTopLevelKeys(exampleFieldName) ?: exampleFields.getValue(exampleFieldName)

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

    fun containsField(exampleFieldName: String): Boolean = requestBodyJSONExample?.hasScalarValueForKey(exampleFieldName) ?: exampleFields.containsKey(exampleFieldName)

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
        return exampleFields.isEmpty() && requestBodyJSONExample == null
    }

    private fun keyHasExample(
        key: String,
        pattern: Pattern,
        defaultExampleResolver: DefaultExampleResolver,
    ): Boolean = this.containsField(key) || defaultExampleResolver.hasExample(pattern)

    private fun keyIsMandatory(key: String): Boolean = !isOptional(key)

    fun stepDownOneLevelInJSONHierarchy(key: String): Row {
        if (requestBodyJSONExample == null) {
            return this
        }

        if (requestBodyJSONExample !is JSONObjectValue) {
            throw ContractException("Example provided is a JSON array, which can't contain key $key")
        }

        val value = requestBodyJSONExample.findFirstChildByPath(withoutOptionality(key)) ?: return withNoJSONObjectExample()

        if (value !is JSONComposite) {
            return withNoJSONObjectExample()
        }

        return this.copy(requestBodyJSONExample = value)
    }

    private fun withNoJSONObjectExample() = this.copy(requestBodyJSONExample = null)

    fun stepDownIntoList(): Row {
        if (requestBodyJSONExample == null) {
            return this
        }

        if (requestBodyJSONExample !is JSONArrayValue) {
            throw ContractException("The example provided is a JSON object, while the specification expects a list")
        }

        val list = requestBodyJSONExample.list

        val firstValue = list.firstOrNull()
        if (firstValue is JSONComposite) {
            return this.copy(requestBodyJSONExample = firstValue as JSONComposite)
        }

        return this.copy(requestBodyJSONExample = null)
    }

    fun isEmpty(): Boolean {
        return exampleFields.isEmpty() && requestBodyJSONExample == null
    }

    fun removeKey(property: String): Row {
        val rowWithoutProperty = copy(exampleFields = exampleFields - property)

        if (rowWithoutProperty.requestBodyJSONExample == null) return rowWithoutProperty

        return rowWithoutProperty.copy(
            requestBodyJSONExample = rowWithoutProperty.requestBodyJSONExample.removeKey(property)
        )
    }

    fun updateRequest(
        request: HttpRequest,
        requestPattern: HttpRequestPattern,
        resolver: Resolver,
    ): Row {
        val path = requestPattern.httpPathPattern?.extractPathParams(request.path.orEmpty(), resolver).orEmpty()
        val headers = request.headers
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
}
