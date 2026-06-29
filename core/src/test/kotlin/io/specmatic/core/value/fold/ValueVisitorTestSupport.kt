package io.specmatic.core.value.fold

import io.specmatic.core.ExampleDeclarations
import io.specmatic.core.NoBodyValue
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.TypeDeclaration
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLValue

sealed interface ValuePathSegment {
    data class Field(val name: String) : ValuePathSegment
    data class Index(val index: Int) : ValuePathSegment
    data class XmlChild(val index: Int) : ValuePathSegment
    data class XmlAttribute(val name: String) : ValuePathSegment
}

internal data class BreadcrumbContext(val path: List<ValuePathSegment> = emptyList()) {
    fun append(segment: ValuePathSegment): BreadcrumbContext = copy(path = path + segment)
}

internal data class DepthContext(val level: Int, val trail: String) {
    fun child(trail: String): DepthContext = copy(level = level + 1, trail = trail)
}

internal class PathTraceVisitor : ValueVisitor<BreadcrumbContext, List<String>> {
    override val rootContext: BreadcrumbContext = BreadcrumbContext()

    override fun opaque(case: OpaqueValueCase<out Value, BreadcrumbContext>): List<String> {
        return listOf("leaf:${case.value.toStringLiteral()}:${case.context.path}")
    }

    override fun fieldObject(case: FieldObjectValueCase<out Value, BreadcrumbContext>): List<String> {
        return listOf("object:${case.context.path}") + case.projectFields(this).flatMap { field ->
            listOf("field:${field.name}") + field.value
        }
    }

    override fun indexedList(case: IndexedListValueCase<out Value, BreadcrumbContext>): List<String> {
        return listOf("list:${case.context.path}") + case.projectItems(this).flatMap { item ->
            listOf("item:${item.index}") + item.value
        }
    }

    override fun xmlElement(case: XmlElementValueCase<out XMLValue, BreadcrumbContext>): List<String> {
        return listOf("xml:${case.context.path}") +
            case.projectAttributes(this).flatMap { attribute ->
                listOf("attr:${attribute.name}") + attribute.value
            } +
            case.projectChildren(this).flatMap { child ->
                listOf("xmlChild:${child.index}") + child.value
            }
    }

    override fun contextForField(currentContext: BreadcrumbContext, name: String): BreadcrumbContext {
        return currentContext.append(ValuePathSegment.Field(name))
    }

    override fun contextForIndex(currentContext: BreadcrumbContext, index: Int): BreadcrumbContext {
        return currentContext.append(ValuePathSegment.Index(index))
    }

    override fun contextForXmlChild(currentContext: BreadcrumbContext, index: Int): BreadcrumbContext {
        return currentContext.append(ValuePathSegment.XmlChild(index))
    }

    override fun contextForXmlAttribute(currentContext: BreadcrumbContext, name: String): BreadcrumbContext {
        return currentContext.append(ValuePathSegment.XmlAttribute(name))
    }
}

internal class UnknownValue : BaseTestValue("unknown")

internal class LeafValue(rendered: String) : BaseTestValue(rendered)

internal class CountingValue(private val onVisit: () -> Unit) : BaseTestValue("counting") {
    override fun <C, R> accept(visitor: ValueVisitor<C, R>, context: C): R {
        onVisit()
        return visitor.opaque(OpaqueValueCase(this, context))
    }
}

internal class TestScalarValue(private val number: Int) : BaseTestValue(number.toString()), ScalarValue {
    override val nativeValue: Any = number

    override fun alterValue(): ScalarValue = TestScalarValue(number + 1)
}

internal class FakeObjectValue(private val rawFields: List<Field<Value>>) : BaseTestValue("object") {
    override fun <C, R> accept(visitor: ValueVisitor<C, R>, context: C): R {
        return visitor.fieldObject(
            FieldObjectValueCase(
                value = this,
                context = context,
                fields = { rawFields },
                rebuild = { fields -> FakeObjectValue(fields) }
            )
        )
    }
}

internal class FakeListValue(private val rawItems: List<Item<Value>>) : BaseTestValue("list") {
    override fun <C, R> accept(visitor: ValueVisitor<C, R>, context: C): R {
        return visitor.indexedList(
            IndexedListValueCase(
                value = this,
                context = context,
                items = { rawItems },
                rebuild = { items -> FakeListValue(items) }
            )
        )
    }
}

internal open class BaseTestValue(private val rendered: String) : Value {
    override val httpContentType: String = "text/plain"

    override fun displayableValue(): String = rendered

    override fun toStringLiteral(): String = rendered

    override fun displayableType(): String = rendered

    override fun exactMatchElseType(): Pattern = StringPattern()

    override fun type(): Pattern = StringPattern()

    override fun typeDeclarationWithoutKey(
        exampleKey: String,
        types: Map<String, Pattern>,
        exampleDeclarations: ExampleDeclarations
    ): Pair<TypeDeclaration, ExampleDeclarations> {
        return TypeDeclaration(rendered, types) to exampleDeclarations
    }

    override fun typeDeclarationWithKey(
        key: String,
        types: Map<String, Pattern>,
        exampleDeclarations: ExampleDeclarations
    ): Pair<TypeDeclaration, ExampleDeclarations> {
        return TypeDeclaration(rendered, types) to exampleDeclarations
    }

    override fun listOf(valueList: List<Value>): Value = NoBodyValue

    override fun specificity(): Int = 1
}
