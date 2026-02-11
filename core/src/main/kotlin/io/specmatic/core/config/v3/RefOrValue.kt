package io.specmatic.core.config.v3

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.databind.JavaType
import io.specmatic.core.config.v3.components.ExampleDirectories
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.utilities.yamlMapper
import kotlin.reflect.jvm.jvmName

interface RefOrValueResolver {
    fun resolveRef(reference: String): Any
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

inline fun <reified T : Any> RefOrValue<T>.resolveElseThrow(resolver: RefOrValueResolver): T {
    val type = yamlMapper.typeFactory.constructType(T::class.java)
    return resolveElseThrowWithType(resolver, type, T::class.simpleName ?: T::class.jvmName)
}

inline fun <reified R : Any, reified S : Any> RefOrValue<CommonServiceConfig<R, S>>.resolveElseThrow(resolver: RefOrValueResolver): CommonServiceConfig<R, S> {
    val type = yamlMapper.typeFactory.constructParametricType(CommonServiceConfig::class.java, R::class.java, S::class.java)
    return resolveElseThrowWithType(
        resolver = resolver,
        type = type,
        typeLabel = "CommonServiceConfig<${R::class.simpleName}, ${S::class.simpleName}>",
    )
}

fun RefOrValue<List<RefOrValue<ExampleDirectories>>>.resolveElseThrow(resolver: RefOrValueResolver): List<RefOrValue<ExampleDirectories>> {
    val exampleRefType = yamlMapper.typeFactory.constructParametricType(RefOrValue::class.java, ExampleDirectories::class.java)
    val examplesType = yamlMapper.typeFactory.constructCollectionType(List::class.java, exampleRefType)
    return resolveElseThrowWithType(
        resolver = resolver,
        type = examplesType,
        typeLabel = "List<RefOrValue<ExampleDirectories>>"
    )
}

fun <T : Any> RefOrValue<T>.resolveElseThrowWithType(resolver: RefOrValueResolver, type: JavaType, typeLabel: String): T {
    val resolvedValue = when (this) {
        is RefOrValue.Value -> return value
        is RefOrValue.Reference -> resolver.resolveRef(ref)
    }

    val combined = if (extra.isNotEmpty()) {
        when (resolvedValue) {
            is Map<*, *> -> resolvedValue.plus(extra)
            else -> throw IllegalStateException("Cannot apply inline overrides to non-object reference: $ref")
        }
    } else {
        resolvedValue
    }

    return runCatching {
        yamlMapper.convertValue(combined, type) as T
    }.getOrElse { e ->
        throw IllegalStateException("Failed to convert resolved value to $typeLabel", e)
    }
}
