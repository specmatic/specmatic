package io.specmatic.core.examples.preprocessor

class PreProcessorAttributes private constructor(private val values: Map<Key<*>, Any>) {
    interface Key<T : Any>

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: Key<T>): T? {
        return values[key] as? T
    }

    fun <T : Any> put(key: Key<T>, value: T): PreProcessorAttributes {
        return PreProcessorAttributes(values + (key to value))
    }

    fun merge(other: PreProcessorAttributes): PreProcessorAttributes {
        return PreProcessorAttributes(values + other.values)
    }

    companion object {
        val Empty = PreProcessorAttributes(emptyMap())
    }
}
