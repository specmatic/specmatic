package io.specmatic.core.substitution

import io.specmatic.core.Substitution
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.breadCrumb
import io.specmatic.core.pattern.listFold
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.Value
import io.specmatic.core.value.fold.Field
import io.specmatic.core.value.fold.FieldObjectValueCase
import io.specmatic.core.value.fold.IndexedListValueCase
import io.specmatic.core.value.fold.Item
import io.specmatic.core.value.fold.OpaqueValueCase
import io.specmatic.core.value.fold.TextValueCase
import io.specmatic.core.value.fold.ValueVisitor

class ValueSubstitutionVisitor(private val substitution: Substitution) : ValueVisitor<Unit, ReturnValue<Value>> {
    override val rootContext: Unit = Unit

    override fun opaque(case: OpaqueValueCase<out Value, Unit>): ReturnValue<Value> {
        return HasValue(case.value)
    }

    override fun text(case: TextValueCase<out Value, Unit>): ReturnValue<Value> {
        return substitution.substitute(case.value)
    }

    override fun fieldObject(case: FieldObjectValueCase<out Value, Unit>): ReturnValue<Value> {
        val resolved = case.fields().map { (name, value) ->
            val resolvedValue = resolve(value).breadCrumb(name)
            resolvedValue.ifValue { Field(name, it) }
        }.listFold()

        return resolved.ifValue { fields -> case.rebuild(fields) }
    }

    override fun indexedList(case: IndexedListValueCase<out Value, Unit>): ReturnValue<Value> {
        val resolved = case.items().map { (index, value) ->
            val resolvedValue = resolve(value).breadCrumb(index.toString())
            resolvedValue.ifValue { Item(index, it) }
        }.listFold()

        return resolved.ifValue { items -> case.rebuild(items) }
    }

    private fun resolve(value: Value): ReturnValue<Value> {
        val resolved = substitution.substitute(value).unwrapOrReturn { return it.cast()}
        return resolved.accept(this)
    }

    companion object {
        fun resolve(value: Value, substitution: Substitution): ReturnValue<Value> {
            val resolver = ValueSubstitutionVisitor(substitution = substitution)
            return resolver.resolve(value)
        }
    }
}
