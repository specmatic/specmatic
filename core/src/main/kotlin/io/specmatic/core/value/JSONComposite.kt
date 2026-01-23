package io.specmatic.core.value

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.ContractException

sealed interface JSONComposite {
    fun checkIfAllRootLevelKeysAreAttributeSelected(attributeSelectedFields: Set<String>, resolver: Resolver): Result

    fun removeKey(key: String): JSONComposite {
        if (this is JSONObjectValue)
            return this.copy(jsonObject = this.jsonObject - key)
        return this
    }

    fun hasScalarValueForKey(key: String): Boolean {
        return this.let {
            it is JSONObjectValue && it.jsonObject[key] is ScalarValue
        }
    }

    fun getValueFromTopLevelKeys(columnName: String): String {
        if (this !is JSONObjectValue)
            throw ContractException("The example provided is a JSON array, while the specification expects a JSON object with key $columnName")

        return this.jsonObject.getValue(columnName).toStringLiteral()
    }
}
