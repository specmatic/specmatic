package io.specmatic.test

import io.ktor.http.*
import io.ktor.util.*
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.QueryParameters
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.test.asserts.isKeyAssert
import java.io.File

const val delayedRandomSubstitutionKey = "\$rand"
val SUBSTITUTE_PATTERN = Regex("^\\$(\\w+)?\\((.*)\\)$")

enum class SubstitutionType { SIMPLE, DELAYED_RANDOM }

enum class StoreType(val type: String, val grammar: String) {
    REPLACE("save", "as"), MERGE("merge", "with");
}

object ExampleProcessor {
    private var runningEntity: Map<String, Value> = mapOf()
    private var factStore: Map<String, Value> = loadConfig().toFactStore("CONFIG")

    private fun loadConfig(): JSONObjectValue {
        val configFilePath = runCatching {
            loadSpecmaticConfig().getAdditionalExampleParamsFilePath()
        }.getOrElse { SpecmaticConfig().getAdditionalExampleParamsFilePath() } ?: return JSONObjectValue(emptyMap())

        val configFile = File(configFilePath)
        if (!configFile.exists()) {
            throw ContractException(breadCrumb = configFilePath, errorMessage = "Could not find the CONFIG at path ${configFile.canonicalPath}")
        }

        return runCatching { parsedJSONObject(configFile.readText()) }.getOrElse { e ->
            throw ContractException(breadCrumb = configFilePath, errorMessage = "Could not parse the CONFIG at path ${configFile.canonicalPath}: ${exceptionCauseMessage(e)}")
        }.also {
            it.findFirstChildByPath("url")?.let {
                url -> System.setProperty("testBaseURL", url.toStringLiteral())
            }
        }
    }

    fun cleanStores() {
        factStore = loadConfig().toFactStore("CONFIG")
        runningEntity = emptyMap()
    }

    @Suppress("MemberVisibilityCanBePrivate") // Being used by other projects
    fun defaultIfNotExits(lookupKey: String, type: SubstitutionType = SubstitutionType.SIMPLE): Value {
        throw ContractException(breadCrumb = lookupKey, errorMessage = "Could not resolve ${lookupKey.quote()}, key does not exist in fact store")
    }

    @Suppress("MemberVisibilityCanBePrivate") // Being used by other projects
    fun ifNotExitsToLookupPattern(lookupKey: String, type: SubstitutionType = SubstitutionType.SIMPLE): Value {
        return when (type) {
            SubstitutionType.SIMPLE -> StringValue("$($lookupKey)")
            SubstitutionType.DELAYED_RANDOM -> StringValue("$delayedRandomSubstitutionKey($lookupKey)")
        }
    }

    fun getFactStore(): Map<String, Value> {
        return factStore + runningEntity
    }

    private fun getValue(key: String, type: SubstitutionType): Value? {
        val returnValue = factStore[key] ?: runningEntity[key]
        if (type != SubstitutionType.DELAYED_RANDOM) return returnValue

        val arrayValue = returnValue as? JSONArrayValue
            ?: throw ContractException(breadCrumb = key, errorMessage = "${key.quote()} is not an array in fact store")

        val entityKey = "ENTITY.${key.substringAfterLast('.')}"
        val entityValue = factStore[entityKey] ?: runningEntity[entityKey]
            ?: throw ContractException(breadCrumb = entityKey, errorMessage = "Could not resolve ${entityKey.quote()} in fact store")

        val filteredList = arrayValue.list.filterNot { it.toStringLiteral() == entityValue.toStringLiteral() }.ifEmpty {
            throw ContractException(breadCrumb = key, errorMessage = "Couldn't pick a random value from ${key.quote()} that was not equal to ${entityValue.displayableValue()}")
        }

        return filteredList.random()
    }

    /* RESOLVER HELPERS */
    fun resolve(row: Row, ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value = ::ifNotExitsToLookupPattern): Row {
        return row.copy(
            exampleFields = row.exampleFields.mapValues { (_, value) ->
                resolve(parsedValue(value), ifNotExists).toStringLiteral()
            },
            requestExample = row.requestExample?.let { resolve(it, ifNotExists) },
            responseExample = row.responseExample?.let { resolve(it, ifNotExists) }
        )
    }

    fun resolve(httpRequest: HttpRequest, ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value = ::defaultIfNotExits): HttpRequest {
        return httpRequest.copy(
            method = httpRequest.method,
            path = httpRequest.parsePath().joinToString("/", prefix = "/", postfix = "/".takeIf { httpRequest.path?.endsWith('/') == true }.orEmpty()) {
                resolve(it, ifNotExists)
            },
            headers = resolve(httpRequest.headers, ifNotExists),
            body = resolve(httpRequest.body, ifNotExists),
            queryParams = QueryParameters(resolve(httpRequest.queryParams.paramPairs, ifNotExists))
        )
    }

    fun resolve(httpResponse: HttpResponse, ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value = ::defaultIfNotExits): HttpResponse {
        return httpResponse.copy(
            status = httpResponse.status,
            headers = resolve(httpResponse.headers, ifNotExists),
            body = resolve(httpResponse.body, ifNotExists)
        )
    }

    fun resolve(entries: List<Pair<String, String>>, ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value): List<Pair<String, String>> {
        val keysToAvoid = entries.groupBy { it.first }.filter { it.value.size > 1 }.keys
        return entries.map { (key, value) ->
            val updatedValue = if (key in keysToAvoid) value else resolve(value, ifNotExists)
            key to updatedValue
        }
    }

    fun resolve(value: Map<String, String>, ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value): Map<String, String> {
        return value.mapValues { (_, value) -> resolve(value, ifNotExists) }
    }

    fun resolve(value: Value, ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value): Value {
        return when (value) {
            is StringValue -> resolve(value, ifNotExists)
            is JSONObjectValue -> resolve(value, ifNotExists)
            is JSONArrayValue -> resolve(value, ifNotExists)
            else -> value
        }
    }

    private fun resolve(value: String, ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value): String {
        return value.ifSubstitutionToken { token, type ->
            if (type == SubstitutionType.DELAYED_RANDOM && ifNotExists == ::ifNotExitsToLookupPattern ) {
                return@ifSubstitutionToken ifNotExitsToLookupPattern(token, type).toStringLiteral()
            } else getValue(token, type)?.toStringLiteral() ?: ifNotExists(token, type).toStringLiteral()
        } ?: value
    }

    fun resolveAny(
        value: Map<String, Any>,
        ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value,
    ): Map<String, Any> {
        return value.mapValues { (_, value) -> resolve(value, ifNotExists) }
    }

    fun resolve(value: Any, ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value): Any {
        if(value is String) return resolve(value, ifNotExists)
        return value
    }

    private fun resolve(value: StringValue, ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value): Value {
        return value.ifSubstitutionToken { token, type ->
            if (type == SubstitutionType.DELAYED_RANDOM && ifNotExists == ::ifNotExitsToLookupPattern) {
                return@ifSubstitutionToken ifNotExitsToLookupPattern(token, type)
            } else getValue(token, type) ?: ifNotExists(token, type)
        } ?: value
    }

    internal fun resolve(value: JSONObjectValue, ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value): JSONObjectValue {
        return JSONObjectValue(value.jsonObject.mapValues { (_, value) -> resolve(value, ifNotExists) })
    }

    private fun resolve(value: JSONArrayValue, ifNotExists: (lookupKey: String, type: SubstitutionType) -> Value): JSONArrayValue {
        return JSONArrayValue(value.list.map { resolve(it, ifNotExists) })
    }

    private fun toStoreErrorMessage(exampleRow: Row, type: StoreType): String {
        return "Could not ${type.type} http response body ${type.grammar} ENTITY for example ${exampleRow.name.quote()}"
    }

    /* STORE HELPERS */
    fun store(exampleRow: Row, httpRequest: HttpRequest, httpResponse: HttpResponse) {
        if (httpRequest.method == "POST") {
            runningEntity = httpResponse.body.toFactStore(prefix = "ENTITY")
            return
        }

        val bodyToCheck = exampleRow.responseExampleForAssertion?.body
        bodyToCheck?.ifContainsStoreToken { type ->
            val valueToStore = httpResponse.body.getJsonObjectIfExists() ?:
                throw ContractException(breadCrumb = exampleRow.name, errorMessage = toStoreErrorMessage(exampleRow, type))

            runningEntity = when (type) {
                StoreType.REPLACE -> valueToStore.toFactStore(prefix = "ENTITY")
                StoreType.MERGE -> runningEntity.plus(valueToStore.toFactStore(prefix = "ENTITY"))
            }
        }
    }

    private fun Value.getJsonObjectIfExists(): JSONObjectValue? {
        return when (this) {
            is JSONObjectValue -> this
            is JSONArrayValue -> this.list.firstOrNull()?.getJsonObjectIfExists()
            else -> null
        }
    }

    fun store(actualValue: Value, exampleValue: Value) {
        exampleValue.ifContainsStoreToken { type ->
            val actualJsonObjectValue = actualValue.getJsonObjectIfExists()
                ?: throw ContractException(errorMessage = "Could not ${type.type} value ${type.grammar} ENTITY")
            runningEntity = when (type) {
                StoreType.REPLACE -> actualJsonObjectValue.toFactStore(prefix = "ENTITY")
                StoreType.MERGE -> runningEntity.plus(actualJsonObjectValue.toFactStore(prefix = "ENTITY"))
            }
        }
    }

    private fun Value.ifContainsStoreToken(block: (storeType: StoreType) -> Unit) {
        val responseBody = this.getJsonObjectIfExists() ?: return
        responseBody.findFirstChildByPath("\$store")?.let {
            when (it.toStringLiteral().toLowerCasePreservingASCIIRules()){
                "merge" -> block(StoreType.MERGE)
                else -> block(StoreType.REPLACE)
            }
        }
    }

    /* PARSER HELPERS */
    @Suppress("MemberVisibilityCanBePrivate") // Being used by other projects
    fun Value.toFactStore(prefix: String = ""): Map<String, Value> {
        return this.traverse(
            prefix = prefix,
            onScalar = { scalar, key -> mapOf(key to scalar) },
            onComposite = { composite, key -> mapOf(key to composite) }
        )
    }

    private fun HttpRequest.parsePath(): List<String> {
        return path?.trim('/')?.split("/") ?: emptyList()
    }

    private fun <T> T.ifSubstitutionToken(block: (lookupKey: String, type: SubstitutionType) -> T): T? {
        return if (isSubstitutionToken(this)) {
            val type = if (this.toString().contains(delayedRandomSubstitutionKey)) {
                SubstitutionType.DELAYED_RANDOM
            } else SubstitutionType.SIMPLE

            block(withoutSubstituteDelimiters(this), type)
        } else null
    }

    fun <T> isSubstitutionToken(token: T): Boolean {
        return when (token) {
            is String -> SUBSTITUTE_PATTERN.matchEntire(token) != null
            is StringValue -> SUBSTITUTE_PATTERN.matchEntire(token.string) != null
            else -> false
        }
    }

    private fun <T> withoutSubstituteDelimiters(token: T): String {
        return when (token) {
            is String -> SUBSTITUTE_PATTERN.find(token)?.groups?.get(2)?.value ?: ""
            is StringValue -> SUBSTITUTE_PATTERN.find(token.string)?.groups?.get(2)?.value ?: ""
            else -> ""
        }
    }
}

fun <T> Value.traverse(
    prefix: String = "", onScalar: (Value, String) -> Map<String, T>,
    onComposite: ((Value, String) -> Map<String, T>)? = null, onAssert: ((Value, String) ->  Map<String, T>)? = null
): Map<String, T> {
    return when (this) {
        is JSONObjectValue -> this.traverse(prefix, onScalar, onComposite, onAssert)
        is JSONArrayValue ->  this.traverse(prefix, onScalar, onComposite, onAssert)
        is ScalarValue -> onScalar(this, prefix)
        else -> emptyMap()
    }.filterValues { it != null }
}

private fun <T> JSONObjectValue.traverse(
    prefix: String = "", onScalar: (Value, String) -> Map<String, T>,
    onComposite: ((Value, String) -> Map<String, T>)? = null, onAssert: ((Value, String) ->  Map<String, T>)? = null
): Map<String, T> {
    return this.jsonObject.entries.flatMap { (key, value) ->
        val fullKey = if (prefix.isNotEmpty()) "$prefix.$key" else key
        key.isKeyAssert {
            onAssert?.invoke(value, fullKey)?.entries.orEmpty()
        } ?: value.traverse(fullKey, onScalar, onComposite, onAssert).entries
    }.associate { it.toPair() } + onComposite?.invoke(this, prefix).orEmpty()
}

private fun <T> JSONArrayValue.traverse(
    prefix: String = "", onScalar: (Value, String) -> Map<String, T>,
    onComposite: ((Value, String) -> Map<String, T>)? = null, onAssert: ((Value, String) ->  Map<String, T>)? = null
): Map<String, T> {
    val listToTraverse = if (onAssert == null) this.list else this.list.lastOrNull()?.let { listOf(it) }.orEmpty()
    return listToTraverse.mapIndexed { index, value ->
        val indexToUse = if (onAssert == null) index else "*"
        value.traverse("$prefix[$indexToUse]", onScalar, onComposite, onAssert)
    }.flatMap { it.entries }.associate { it.toPair() } + onComposite?.invoke(this, prefix).orEmpty()
}
