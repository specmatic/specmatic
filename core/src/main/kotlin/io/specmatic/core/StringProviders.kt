package io.specmatic.core

import io.specmatic.core.pattern.ScalarType
import io.specmatic.test.asserts.WILDCARD_INDEX
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

interface StringProvider {
    fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>): String?
}

object StringProviders {
    private val providers = CopyOnWriteArrayList<StringProvider>()

    init {
        ServiceLoader.load(StringProvider::class.java).forEach {
            providers.add(it)
        }
    }

    fun add(provider: StringProvider) {
        providers.add(provider)
    }

    fun getFor(pattern: ScalarType, resolver: Resolver): Sequence<String> {
        val path = resolver.dictionaryLookupPath.replace(WILDCARD_INDEX, "|$WILDCARD_INDEX|").split(".", "|")
        val reversedPath = path.filter(String::isNotEmpty).reversed()
        return providers.asSequence().mapNotNull {
            runCatching { it.getFor(pattern, resolver, reversedPath) }.getOrNull()
        }
    }

    fun with(provider: StringProvider, block: () -> Unit) {
        providers.add(provider)
        try {
            block()
        } finally {
            providers.remove(provider)
        }
    }
}