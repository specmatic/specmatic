package io.specmatic.core.config

import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class EmptyConfigCollectionFilter {
    override fun equals(other: Any?): Boolean {
        if (other == null) return true
        if (other.javaClass == this.javaClass) return true
        return when (other) {
            is String -> other.isBlank()
            is Array<*> -> other.all { equals(it) }
            is Collection<*> -> other.all { equals(it) }
            is Map<*, *> -> other.all { it.key is String && equals(it.value) }
            else -> isEmptyDataClass(other)
        }
    }

    private fun isEmptyDataClass(obj: Any): Boolean {
        val kClass: KClass<*> = obj::class
        if (!kClass.isData) return false
        return kClass.memberProperties.all { prop ->
            prop.isAccessible = true
            val value = prop.call(obj)
            equals(value)
        }
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }
}
