package io.specmatic.core.config.v3

import com.fasterxml.jackson.annotation.*
import io.specmatic.core.utilities.yamlMapper

interface RefOrValueResolver {
    fun resolveRef(reference: String): Map<*, *>
}

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = RefOrValue.Value::class)
sealed interface RefOrValue<out T: Any> {
    data class Reference(@param:JsonProperty("\$ref") val ref: String, @JsonIgnore val extra: Map<String, Any?> = emptyMap()) : RefOrValue<Nothing> {
        @Suppress("unused")
        @JsonAnyGetter
        fun extraProperties(): Map<String, Any?> = extra

        companion object {
            @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
            @JvmStatic
            fun create(@JsonProperty("\$ref") ref: String, @JsonAnySetter extraProps: Map<String, Any?>): Reference {
                return Reference(ref, extraProps)
            }
        }
    }

    data class Value<out T : Any>(@param:JsonUnwrapped @JsonValue val value: T) : RefOrValue<T> {
        companion object {
            @JvmStatic
            @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
            fun <T :Any> create(value: T): Value<T> = Value(value)
        }
    }

    @JsonIgnore
    fun getUnsafe(): T = when(this) {
        is Value -> this.value
        else -> throw IllegalStateException("Ref has not been resolved cannot return concrete value")
    }

    @JsonIgnore
    fun getOrNull(): T? = when(this) {
        is Value -> this.value
        else -> null
    }

    companion object {
        inline fun <reified R : Any> RefOrValue<R>.resolve(resolveReference: (String) -> R): R = when (this) {
            is Value -> this.value
            is Reference -> resolveReference(this.ref)
        }
    }
}

inline fun <reified T : Any> RefOrValue<T>.resolveElseThrow(resolver: RefOrValueResolver): T = resolve(resolver, T::class.java).getOrThrow()

inline fun <reified T : Any> RefOrValue<T>.resolve(resolver: RefOrValueResolver): Result<T> = resolve(resolver, T::class.java)

fun <T : Any> RefOrValue<T>.resolve(resolver: RefOrValueResolver, clazz: Class<T>): Result<T> {
    val baseValue = when (this) {
        is RefOrValue.Value -> return Result.success(value)
        is RefOrValue.Reference -> resolver.resolveRef(ref)
    }

    val combined = baseValue.plus(extra)
    return runCatching { Result.success(yamlMapper.convertValue(combined, clazz)) }.getOrElse { e ->
        val exception = java.lang.IllegalStateException("Failed to convert resolved to value to ${clazz.simpleName}", e)
        Result.failure(exception)
    }
}
