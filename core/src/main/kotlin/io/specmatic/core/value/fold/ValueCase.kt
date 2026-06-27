package io.specmatic.core.value.fold

import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLValue

sealed interface ValueCase<out V : Value, out C> {
    val value: V
    val context: C
}

data class ScalarValueCase<V : Value, C>(
    val nativeValue: Any?,
    override val value: V,
    override val context: C,
) : ValueCase<V, C>

data class TextValueCase<V : Value, C>(
    val text: String,
    override val value: V,
    override val context: C,
    val replaceText: (String) -> V,
) : ValueCase<V, C>


data class FieldObjectValueCase<V : Value, C>(
    override val value: V,
    override val context: C,
    val fields: () -> List<Field<Value>>,
    val rebuild: (List<Field<Value>>) -> V,
) : ValueCase<V, C> {
    fun <R> projectFields(visitor: ValueVisitor<C, R>): List<Field<R>> {
        return fields().map { field ->
            Field(
                name = field.name,
                value = field.value.accept(visitor = visitor, context = visitor.contextForField(context, field.name))
            )
        }
    }
}

data class IndexedListValueCase<V : Value, C>(
    override val value: V,
    override val context: C,
    val items: () -> List<Item<Value>>,
    val rebuild: (List<Item<Value>>) -> V,
) : ValueCase<V, C> {
    fun <R> projectItems(visitor: ValueVisitor<C, R>): List<Item<R>> {
        return items().map { item ->
            Item(
                index = item.index,
                value = item.value.accept(visitor = visitor, context = visitor.contextForIndex(context, item.index))
            )
        }
    }
}

data class XmlElementValueCase<V : XMLValue, C>(
    override val value: V,
    override val context: C,
    val children: () -> List<XmlValueChild<XMLValue>>,
    val attributes: () -> List<XmlAttribute<StringValue>>,
    val rebuild: (List<XmlAttribute<StringValue>>, List<XmlValueChild<XMLValue>>) -> V
) : ValueCase<V, C> {
    fun <R> projectAttributes(visitor: ValueVisitor<C, R>): List<XmlAttribute<R>> {
        return attributes().map { attribute ->
            XmlAttribute(
                name = attribute.name,
                value = attribute.value.accept(
                    visitor = visitor,
                    context = visitor.contextForXmlAttribute(context, attribute.name)
                )
            )
        }
    }

    fun <R> projectChildren(visitor: ValueVisitor<C, R>): List<XmlProjectedChild<R>> {
        return children().map { child ->
            XmlProjectedChild(
                index = child.index,
                value = child.value.accept(visitor = visitor, context = visitor.contextForXmlChild(context, child.index))
            )
        }
    }
}

data class OpaqueValueCase<V : Value, C>(
    override val value: V,
    override val context: C,
) : ValueCase<V, C> {
    val kind: String = value::class.simpleName ?: "Value"
}
