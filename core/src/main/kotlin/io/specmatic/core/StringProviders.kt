package io.specmatic.core

import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

interface StringProvider {
    fun getFor(path: List<String>): String?
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

    fun getFor(path: List<String>): Sequence<String> {
        return providers.asSequence().mapNotNull {
            runCatching { it.getFor(path) }.getOrNull()
        }
    }
}