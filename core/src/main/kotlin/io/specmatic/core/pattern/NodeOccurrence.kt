package io.specmatic.core.pattern

import io.specmatic.core.Result

// Specmatic's XML element occurrence model is intentionally collapsed to these
// three cases. Code paths that preserve explicit minOccurs/maxOccurs, such as
// choice groups and wildcards, should use those fields directly.
enum class NodeOccurrence {
    Multiple {
        override fun encompasses(otherTypeOccurrence: NodeOccurrence): Result {
            return when(otherTypeOccurrence) {
                Optional, Multiple -> Result.Success()
                else -> Result.Failure("This node $description whereas the other ${otherTypeOccurrence.description}.")
            }
        }

        override val description: String = "may occur 0 or more times"
    },
    Optional {
        override fun encompasses(otherTypeOccurrence: NodeOccurrence): Result {
            return when(otherTypeOccurrence) {
                Once, Optional -> Result.Success()
                else -> Result.Failure("This node $description whereas the other ${otherTypeOccurrence.description}.")
            }
        }

        override val description: String = "is optional"
    },
    Once {
        override fun encompasses(otherTypeOccurrence: NodeOccurrence): Result {
            return when(otherTypeOccurrence) {
                Once -> Result.Success()
                else -> Result.Failure("This node $description whereas the other ${otherTypeOccurrence.description}.")
            }
        }

        override val description: String = "must occur"
    };

    abstract fun encompasses(otherTypeOccurrence: NodeOccurrence): Result
    abstract val description: String
}
