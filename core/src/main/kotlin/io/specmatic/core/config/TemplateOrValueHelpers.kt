package io.specmatic.core.config

import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

internal fun <T : Any> wrap(value: T): TemplateOrValue<T> = TemplateOrValue.Value(value)

internal inline fun <reified Item : Any> List<Item>.wrapFully(): TemplateOrValue<List<TemplateOrValue<Item>>> {
    return wrap(this.map(::wrap))
}

internal inline fun <reified Item: Any> TemplateOrValue<List<TemplateOrValue<Item>>>.resolveFully(): List<Item> {
    return this.resolve().map { it.resolve() }
}

internal inline fun <reified Item: Any> TemplateOrValue<Set<TemplateOrValue<Item>>>.resolveFully(): Set<Item> {
    return this.resolve().map { it.resolve() }.toSet()
}

internal inline fun <reified Key : Any, reified Value : Any> Map<Key, Value>.wrapFully(): TemplateOrValue<Map<Key, TemplateOrValue<Value>>> {
    return wrap(this.mapValues { wrap(it.value) })
}

internal inline fun <reified Key: Any, reified Value: Any> TemplateOrValue<Map<Key, TemplateOrValue<Value>>>.resolveFully(): Map<Key, Value> {
    return this.resolve().mapValues { it.value.resolve() }
}
