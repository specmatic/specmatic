package io.specmatic.core.pattern

import io.specmatic.core.Result

/** Legacy XML occurrence markers, interpreted using the shared occurrence ranges. */
enum class NodeOccurrence(val description: String) {
    Multiple("may occur 0 or more times"),
    Optional("is optional"),
    Once("must occur");

    fun encompasses(otherTypeOccurrence: NodeOccurrence): Result =
        if (toOccurrenceRange().encompasses(otherTypeOccurrence.toOccurrenceRange())) Result.Success()
        else Result.Failure("This node $description whereas the other ${otherTypeOccurrence.description}.")
}
