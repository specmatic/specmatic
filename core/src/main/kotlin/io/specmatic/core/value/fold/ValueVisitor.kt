package io.specmatic.core.value.fold

import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLValue

interface ValueVisitor<C, R> {
    val rootContext: C

    fun opaque(case: OpaqueValueCase<out Value, C>): R

    fun scalar(case: ScalarValueCase<out Value, C>): R {
        return opaque(OpaqueValueCase(value = case.value, context = case.context))
    }

    fun text(case: TextValueCase<out Value, C>): R {
        return opaque(OpaqueValueCase(value = case.value, context = case.context))
    }

    fun fieldObject(case: FieldObjectValueCase<out Value, C>): R {
        return opaque(OpaqueValueCase(value = case.value, context = case.context))
    }

    fun indexedList(case: IndexedListValueCase<out Value, C>): R {
        return opaque(OpaqueValueCase(value = case.value, context = case.context))
    }

    fun xmlElement(case: XmlElementValueCase<out XMLValue, C>): R {
        return opaque(OpaqueValueCase(value = case.value, context = case.context))
    }

    fun contextForOpaque(currentContext: C, name: String): C = currentContext

    fun contextForField(currentContext: C, name: String): C = currentContext

    fun contextForIndex(currentContext: C, index: Int): C = currentContext

    fun contextForXmlChild(currentContext: C, index: Int): C = currentContext

    fun contextForXmlAttribute(currentContext: C, name: String): C = currentContext
}
