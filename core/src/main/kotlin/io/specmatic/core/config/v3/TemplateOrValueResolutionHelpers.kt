package io.specmatic.core.config.v3

import com.fasterxml.jackson.core.type.TypeReference
import io.specmatic.core.config.ConfigTemplateUtils
import io.specmatic.core.config.v3.components.ExampleDirectories
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.utilities.yamlMapper

fun <T : Any> T.wrap(): TemplateOrValue<T> {
    return TemplateOrValue.Value(this)
}

fun <T : Any> T?.wrapOrNull(): TemplateOrValue<T>? {
    return this?.let { TemplateOrValue.Value(it) }
}

fun <T : Any> List<T>.wrapFully(): TemplateOrValue<List<TemplateOrValue<T>>> {
    return TemplateOrValue.Value(this.map { TemplateOrValue.Value(it) })
}

fun <T : Any> List<T>?.wrapFullyOrNull(): TemplateOrValue<List<TemplateOrValue<T>>>? {
    return this?.wrapFully()
}

fun <T : Any> Map<String, T>.wrapValuesFully(): TemplateOrValue<Map<String, TemplateOrValue<T>>> {
    return TemplateOrValue.Value(this.mapValues { (_, value) -> TemplateOrValue.Value(value) })
}

fun <T : Any> Map<String, T>?.wrapValuesFullyOrNull(): TemplateOrValue<Map<String, TemplateOrValue<T>>>? {
    return this?.wrapValuesFully()
}

inline fun <reified T : Any> RefOrValue<T>.wrapRef(): TemplateOrValue<RefOrValue<T>> {
    return TemplateOrValue.Value(this)
}

inline fun <reified T : Any> RefOrValue<T>?.wrapRefOrNull(): TemplateOrValue<RefOrValue<T>>? {
    return this?.wrapRef()
}

inline fun <reified T : Any> List<RefOrValue<T>>.wrapRefListFully(): TemplateOrValue<List<TemplateOrValue<RefOrValue<T>>>> {
    return TemplateOrValue.Value(this.map { TemplateOrValue.Value(it) })
}

inline fun <reified T : Any> List<RefOrValue<T>>?.wrapRefListFullyOrNull(): TemplateOrValue<List<TemplateOrValue<RefOrValue<T>>>>? {
    return this?.wrapRefListFully()
}

inline fun <reified T : Any> Map<String, RefOrValue<T>>.wrapRefMapValuesFully(): TemplateOrValue<Map<String, TemplateOrValue<RefOrValue<T>>>> {
    return TemplateOrValue.Value(this.mapValues { (_, value) -> TemplateOrValue.Value(value) })
}

inline fun <reified T : Any> Map<String, RefOrValue<T>>?.wrapRefMapValuesFullyOrNull(): TemplateOrValue<Map<String, TemplateOrValue<RefOrValue<T>>>>? {
    return this?.wrapRefMapValuesFully()
}

inline fun <reified T : Any> TemplateOrValue<T>?.resolveOrNull(): T? {
    return when (this) {
        is TemplateOrValue.Value -> value
        is TemplateOrValue.Template -> resolveTemplateValue(template)
        null -> null
    }
}

inline fun <reified T : Any> TemplateOrValue<T>?.resolveOrDefault(default: T): T {
    return resolveOrNull() ?: default
}

inline fun <reified T : Any> TemplateOrValue<List<TemplateOrValue<T>>>?.resolveFullyOrEmpty(): List<T> {
    return resolveFullyOrNull() ?: emptyList()
}

inline fun <reified T : Any> TemplateOrValue<List<TemplateOrValue<T>>>?.resolveFullyOrNull(): List<T>? {
    return resolveContainerOrNull(
        resolveValue = { values -> values.map { it.resolveOrNullRequired() } },
        resolveTemplate = { template -> resolveTemplateValue<List<TemplateOrValue<T>>>(template).map { it.resolveOrNullRequired() } },
    )
}

inline fun <reified T : Any> TemplateOrValue<Map<String, TemplateOrValue<T>>>?.resolveMapValuesOrEmpty(): Map<String, T> {
    return resolveMapValuesOrNull() ?: emptyMap()
}

inline fun <reified T : Any> TemplateOrValue<Map<String, TemplateOrValue<T>>>?.resolveMapValuesOrNull(): Map<String, T>? {
    return resolveContainerOrNull(
        resolveValue = { values -> values.mapValues { (_, v) -> v.resolveOrNullRequired() } },
        resolveTemplate = { template -> resolveTemplateValue<Map<String, TemplateOrValue<T>>>(template).mapValues { (_, v) -> v.resolveOrNullRequired() } },
    )
}

inline fun <reified T : Any> TemplateOrValue<RefOrValue<T>>?.resolveRefOrNull(
    resolver: RefOrValueResolver
): T? {
    return when (this) {
        is TemplateOrValue.Value -> value.resolveElseThrow(resolver)
        is TemplateOrValue.Template -> resolveTemplateValue<RefOrValue<T>>(template).resolveElseThrow(resolver)
        null -> null
    }
}

inline fun <reified T : Any> TemplateOrValue<List<TemplateOrValue<RefOrValue<T>>>>?.resolveRefListOrEmpty(
    resolver: RefOrValueResolver
): List<T> {
    return resolveRefListOrNull(resolver) ?: emptyList()
}

inline fun <reified T : Any> TemplateOrValue<List<TemplateOrValue<RefOrValue<T>>>>?.resolveRefListOrNull(
    resolver: RefOrValueResolver
): List<T>? {
    return resolveContainerOrNull(
        resolveValue = { values -> values.map { it.resolveRefItem(resolver) } },
        resolveTemplate = { template -> resolveTemplateValue<List<TemplateOrValue<RefOrValue<T>>>>(template).map { it.resolveRefItem(resolver) } },
    )
}

inline fun <reified T : Any> TemplateOrValue<Map<String, TemplateOrValue<RefOrValue<T>>>>?.resolveRefMapValuesOrEmpty(
    resolver: RefOrValueResolver
): Map<String, T> {
    return resolveRefMapValuesOrNull(resolver) ?: emptyMap()
}

inline fun <reified T : Any> TemplateOrValue<Map<String, TemplateOrValue<RefOrValue<T>>>>?.resolveRefMapValuesOrNull(
    resolver: RefOrValueResolver
): Map<String, T>? {
    return resolveContainerOrNull(
        resolveValue = { values -> values.mapValues { (_, item) -> item.resolveRefItem(resolver) } },
        resolveTemplate = { template -> resolveTemplateValue<Map<String, TemplateOrValue<RefOrValue<T>>>>(template).mapValues { (_, item) -> item.resolveRefItem(resolver) } },
    )
}

inline fun <reified T : Any> resolveTemplateValue(template: String): T {
    return yamlMapper.readValue(ConfigTemplateUtils.resolveTemplateValue(template), object : TypeReference<T>() {})
}

inline fun <reified T : Any> TemplateOrValue<T>.resolveOrNullRequired(): T {
    return resolveOrNull() ?: error("Resolved value was null")
}

inline fun <reified T : Any, R : Any> TemplateOrValue<T>?.resolveContainerOrNull(
    resolveValue: (T) -> R,
    resolveTemplate: (String) -> R,
): R? {
    return when (this) {
        is TemplateOrValue.Value -> resolveValue(value)
        is TemplateOrValue.Template -> resolveTemplate(template)
        null -> null
    }
}

inline fun <reified T : Any> TemplateOrValue<RefOrValue<T>>.resolveRefItem(
    resolver: RefOrValueResolver
): T {
    return when (this) {
        is TemplateOrValue.Value -> value.resolveElseThrow(resolver)
        is TemplateOrValue.Template -> resolveTemplateValue<RefOrValue<T>>(template).resolveElseThrow(resolver)
    }
}

inline fun <reified R : Any> TemplateOrValue<R>.resolve(): R {
    return resolveTemplateOrValue(
        resolveValue = { it },
        resolveTemplate = { resolved -> yamlMapper.readValue(resolved, object : TypeReference<R>() {}) },
    )
}

inline fun <reified R : Any> TemplateOrValue<RefOrValue<R>>.resolveElseThrow(resolver: RefOrValueResolver): R {
    return resolveTemplateOrValue(
        resolveValue = { refOrValue -> refOrValue.resolveElseThrow(resolver) },
        resolveTemplate = { resolved ->
            yamlMapper
                .readValue(resolved, object : TypeReference<RefOrValue<R>>() {})
                .resolveElseThrow(resolver)
        },
    )
}

inline fun <reified R : Any, reified S : Any> TemplateOrValue<RefOrValue<CommonServiceConfig<R, S>>>.resolveElseThrow(
    resolver: RefOrValueResolver
): CommonServiceConfig<R, S> {
    return resolveTemplateOrValue(
        resolveValue = { refOrValue -> refOrValue.resolveElseThrow(resolver) },
        resolveTemplate = { resolved ->
            yamlMapper
                .readValue(resolved, object : TypeReference<RefOrValue<CommonServiceConfig<R, S>>>() {})
                .resolveElseThrow(resolver)
        },
    )
}

fun TemplateOrValue<RefOrValue<List<RefOrValue<ExampleDirectories>>>>.resolveElseThrow(
    resolver: RefOrValueResolver
): List<RefOrValue<ExampleDirectories>> {
    return resolveTemplateOrValue(
        resolveValue = { refOrValue -> refOrValue.resolveElseThrow(resolver) },
        resolveTemplate = { resolved ->
            yamlMapper
                .readValue(resolved, object : TypeReference<RefOrValue<List<RefOrValue<ExampleDirectories>>>>() {})
                .resolveElseThrow(resolver)
        },
    )
}

inline fun <T : Any, R : Any> TemplateOrValue<T>.resolveTemplateOrValue(
    resolveValue: (T) -> R,
    resolveTemplate: (String) -> R,
): R {
    return when (this) {
        is TemplateOrValue.Value -> resolveValue(value)
        is TemplateOrValue.Template -> {
            val resolved = ConfigTemplateUtils.resolveTemplateValue(template)
            resolveTemplate(resolved)
        }
    }
}
