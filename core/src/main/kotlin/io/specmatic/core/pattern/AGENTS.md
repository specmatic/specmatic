# Pattern changes

When changing Pattern behavior, audit the related operations identified in [Pattern.kt](Pattern.kt) KDoc.

MismatchMessages controls diagnostic wording only. Do not use its identity or implementation type to select matching, generation, or compatibility behavior. Use pattern semantics or explicit operational context when behavior must differ.
