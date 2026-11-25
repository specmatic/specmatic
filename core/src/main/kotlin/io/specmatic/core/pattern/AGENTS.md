# Pattern changes

When changing Pattern behavior, audit the related operations identified in [Pattern.kt](Pattern.kt) KDoc.

MismatchMessages controls diagnostic wording only. Do not use its identity or implementation type to select matching, generation, or compatibility behavior. Use pattern semantics or explicit operational context when behavior must differ.

For a new pattern or a change to existing pattern behavior, add tests that pass the value returned by `generate(resolver)` to `matches(value, resolver)` on the same pattern and assert a successful match. Cover the variants affected by the change.
