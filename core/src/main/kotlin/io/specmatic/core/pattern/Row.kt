package io.specmatic.core.pattern

import io.specmatic.core.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONComposite
import io.specmatic.core.value.JSONObjectValue

const val DEREFERENCE_PREFIX = "$"
const val FILENAME_PREFIX = "@"
const val REQUEST_BODY_FIELD = "(REQUEST-BODY)"

data class Row(
    val columnNames: List<String> = emptyList(),
    val values: List<String> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
    val references: Map<String, References> = emptyMap(),
    val name: String = "",
    val fileSource: String? = null,
    val requestBodyJSONExample: JSONExample? = null,
    val responseExampleForAssertion: HttpResponse? = null,
    val exactResponseExample: ResponseExample? = null,
    val requestExample: HttpRequest? = null,
    val responseExample: HttpResponse? = null,
    val isPartial: Boolean = false
) {
    constructor(examples: Map<String, String>) :this(examples.keys.toList(), examples.values.toList())

    private val cells = columnNames.zip(values.map { it }).toMap().toMutableMap()

    fun noteRequestBody(): Row {
        if(!this.containsField("(REQUEST-BODY)"))
            return this

        val requestBody = this.getField("(REQUEST-BODY)").trim()

        return try {
            val parsed = parsedJSON(requestBody)

            if(parsed is JSONComposite)
                this.copy(requestBodyJSONExample = JSONExample(parsed, this))
            else
                this
        } catch (e: ContractException) {
            this
        }
    }

    fun getField(columnName: String): String {
        return getValue(columnName).fetch()
    }

    fun getFieldOrNull(columnName: String): String? {
        if (!containsField(columnName)) return null
        return getField(columnName)
    }

    private fun getValue(columnName: String): RowValue {
        val value = requestBodyJSONExample?.getValueFromTopLevelKeys(columnName) ?: cells.getValue(columnName)

        return when {
            isContextValue(value) && isReferenceValue(value) -> ReferenceValue(ValueReference(value), references)
            isContextValue(value) -> VariableValue(ValueReference(value), variables)
            isFileValue(value) -> FileValue(withoutPatternDelimiters(value).removePrefix("@"))
            else -> SimpleValue(value)
        }
    }

    private fun isFileValue(value: String): Boolean {
        return isPatternToken(value) && withoutPatternDelimiters(value).startsWith(FILENAME_PREFIX)
    }

    private fun isReferenceValue(value: String): Boolean = value.contains(".")

    private fun isContextValue(value: String): Boolean {
        return isPatternToken(value) && withoutPatternDelimiters(value).trim().startsWith(DEREFERENCE_PREFIX)
    }

    fun containsField(key: String): Boolean = requestBodyJSONExample?.hasScalarValueForKey(key) ?: cells.containsKey(key)

    fun withoutOmittedKeys(keys: Map<String, Pattern>, defaultExampleResolver: DefaultExampleResolver): Map<String, Pattern> {
        if(this.hasNoRequestExamples() && this.fileSource == null)
            return keys

        return keys.filter { (key, pattern) ->
            keyIsMandatory(key) || keyHasExample(withoutOptionality(key), pattern, defaultExampleResolver)
        }
    }

    private fun hasNoRequestExamples() = columnNames.isEmpty() && requestBodyJSONExample == null

    private fun keyHasExample(key: String, pattern: Pattern, defaultExampleResolver: DefaultExampleResolver): Boolean {
        return this.containsField(key) ||  defaultExampleResolver.hasExample(pattern)
    }

    private fun keyIsMandatory(key: String): Boolean {
        return !isOptional(key)
    }

    fun stepDownOneLevelInJSONHierarchy(key: String): Row {
        if(requestBodyJSONExample == null)
            return this

        if(requestBodyJSONExample.jsonObject !is JSONObjectValue)
            throw ContractException("Example provided is a JSON array, which can't contain key $key")

        val value = requestBodyJSONExample.jsonObject.findFirstChildByPath(withoutOptionality(key)) ?: return withNoJSONObjectExample()

        if(value !is JSONComposite)
            return withNoJSONObjectExample()

        return this.copy(requestBodyJSONExample = JSONExample(value, requestBodyJSONExample.originalRow))
    }

    private fun withNoJSONObjectExample() = this.copy(requestBodyJSONExample = null)

    fun stepDownIntoList(): Row {
        if(requestBodyJSONExample == null)
            return this

        if(requestBodyJSONExample.jsonObject !is JSONArrayValue)
            throw ContractException("The example provided is a JSON object, while the specification expects a list")

        val list = requestBodyJSONExample.jsonObject.list

        val firstValue = list.firstOrNull()
        if(firstValue is JSONComposite)
            return this.copy(requestBodyJSONExample = JSONExample(firstValue as JSONComposite, requestBodyJSONExample.originalRow))

        return this.copy(requestBodyJSONExample = null)
    }

    fun addFields(params: Map<String, String>): Row {
        return params.entries.fold(this) { row, (key, value) ->
            val newColumns = row.columnNames + key
            val newValues = row.values + value

            row.copy(columnNames = newColumns, values = newValues)
        }
    }

    fun hasRequestParameters(): Boolean {
        return values.isNotEmpty() || requestBodyJSONExample != null
    }

    fun isEmpty(): Boolean {
        return columnNames.isEmpty() && values.isEmpty() && requestBodyJSONExample == null
    }

    fun removeKey(property: String): Row {
        val columnIndex = columnNames.indexOf(property)

        val withoutColumn = if(columnIndex >= 0) {
            this.copy(columnNames = columnNames.filterIndexed { index, _ -> index != columnIndex }, values = values.filterIndexed { index, _ -> index != columnIndex })
        } else {
            this
        }

        return withoutColumn.requestBodyJSONExample?.let { jsonExample ->
            val updatedJSONExample = jsonExample.removeKey(property)
            withoutColumn.copy(requestBodyJSONExample = updatedJSONExample)
        } ?: withoutColumn
    }

    fun updateRequest(request: HttpRequest, requestPattern: HttpRequestPattern, resolver: Resolver): Row {
        val path = requestPattern.httpPathPattern?.extractPathParams(request.path.orEmpty(), resolver).orEmpty()
        val headers = request.headers
        val queryParams = request.queryParams.asValueMap().mapValues { it.value.toStringLiteral() }
        val bodyEntry = if (request.body !is NoBodyValue) {
            mapOf(REQUEST_BODY_FIELD to request.body.toStringLiteral())
        } else emptyMap()

        return this.copy(columnNames = emptyList(), values = emptyList()).addFields(path + headers + queryParams + bodyEntry).copy(requestExample = request)
    }
}
