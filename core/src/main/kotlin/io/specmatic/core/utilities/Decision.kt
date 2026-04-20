package io.specmatic.core.utilities

sealed interface Decision<out Item, out Ctx> {
    val context: Ctx
    val reasoning: Reasoning

    data class Execute<Item, Ctx>(val value: Item, override val context: Ctx, override val reasoning: Reasoning = Reasoning()) : Decision<Item, Ctx>
    data class Skip<Ctx>(override val context: Ctx, override val reasoning: Reasoning) : Decision<Nothing, Ctx>

    companion object {
        fun <Item> execute(item: Item): Execute<Item, Item> = Execute(item, item)
    }
}

inline fun <Item, Ctx, NewItem> Decision<Item, Ctx>.flatMap(fn: (Item, Ctx, Reasoning) -> Decision<NewItem, Ctx>): Decision<NewItem, Ctx> = when (this) {
    is Decision.Execute -> fn(value, context, reasoning)
    is Decision.Skip -> Decision.Skip(context, reasoning)
}

inline fun <Item, Ctx, NewItem> Decision<Item, Ctx>.mapValue(fn: (Item) -> NewItem): Decision<NewItem, Ctx> = when (this) {
    is Decision.Execute -> Decision.Execute(fn(value), context, reasoning)
    is Decision.Skip -> Decision.Skip(context, reasoning)
}

inline fun <Item, Ctx, NewItem> Decision<Item, Ctx>.map(fn: (Item, Ctx) -> NewItem): Decision<NewItem, Ctx> = when (this) {
    is Decision.Execute -> Decision.Execute(fn(value, context), context, reasoning)
    is Decision.Skip -> Decision.Skip(context, reasoning)
}

inline fun <Item, Ctx, NewItem> Decision<Item, Ctx>.flatMapSequence(fn: (Item, Ctx, Reasoning) -> Sequence<Decision<NewItem, Ctx>>): Sequence<Decision<NewItem, Ctx>> = when (this) {
    is Decision.Execute -> fn(value, context, reasoning)
    is Decision.Skip -> sequenceOf(Decision.Skip(context, reasoning))
}

inline fun <Item, Ctx, NewItem> Sequence<Decision<Item, Ctx>>.mapSequence(crossinline fn: (Item) -> NewItem): Sequence<Decision<NewItem, Ctx>> {
    return this.map { it.mapValue(fn) }
}

inline fun <Item, Ctx, NewItem> Sequence<Decision<Item, Ctx>>.flatMapSequence(crossinline fn: (Item, Ctx, Reasoning) -> Sequence<Decision<NewItem, Ctx>>): Sequence<Decision<NewItem, Ctx>> {
    return flatMap { it.flatMapSequence(fn) }
}
