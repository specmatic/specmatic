package io.specmatic.core

import java.util.concurrent.CopyOnWriteArrayList

interface StringProvider {
    fun getFor(path: List<String>): String?
}

object StringProviders {
    private val providers = CopyOnWriteArrayList<StringProvider>()

    fun add(provider: StringProvider) {
        providers.add(provider)
    }

    fun getFor(path: List<String>, match: (String) -> Boolean): String? {
        for (provider in providers) {
            val value = provider.getFor(path)
            if (value != null && match(value)) {
                return value
            }
        }
        return null
    }
}