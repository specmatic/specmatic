package io.specmatic.core

import kotlinx.serialization.Serializable

@Serializable
data class RuleViolationSnapshot(
    val id: String,
    val title: String,
    val summary: String?
)

interface RuleViolation {
    val id: String
    val title: String
    val summary: String?

    fun snapshot(): RuleViolationSnapshot = RuleViolationSnapshot(id, title, summary)
}

enum class OpenApiRuleViolation(override val id: String, override val title: String, override val summary: String? = null): RuleViolation {
    METHOD_MISMATCH(
        id = "R0001",
        title = "HTTP method mismatch",
        summary = "The HTTP method does not match the method defined in the specification"
    ),

    STATUS_MISMATCH(
        id = "R0002",
        title = "HTTP status mismatch",
        summary = "The HTTP status code does not match the expected status code defined in the specification"
    ),

    PATH_MISMATCH(
        id = "R0003",
        title = "HTTP path mismatch",
        summary = "The HTTP path does not match any path defined in the specification"
    ),

    SECURITY_SCHEME_MISMATCH(
        id = "R0006",
        title = "Security scheme mismatch",
        summary = "The security scheme does not match the security scheme defined in the specification"
    ),

    NO_MATCHING_SECURITY_SCHEME(
        id = "R0007",
        title = "No matching security scheme",
        summary = "The request does not satisfy the requirements of any defined security scheme"
    ),
}

enum class StandardRuleViolation(override val id: String, override val title: String, override val summary: String? = null): RuleViolation {
    /* ---------------- Pattern → Value Rules ---------------- */
    TYPE_MISMATCH(
        id = "R1001",
        title = "Type mismatch",
        summary = "The value type does not match the expected type defined in the specification"
    ),

    VALUE_MISMATCH(
        id = "R1002",
        title = "Value mismatch",
        summary = "The value does not match the expected value defined in the specification"
    ),

    CONSTRAINT_VIOLATION(
        id = "R1003",
        title = "Constraint violation",
        summary = "The value does not satisfy the constraints defined in the specification"
    ),

    /* ---------------- Key Rules ---------------- */
    REQUIRED_PROPERTY_MISSING(
        id = "R2001",
        title = "Missing required property",
        summary = "A required property defined in the specification is missing"
    ),

    OPTIONAL_PROPERTY_MISSING(
        id = "R2002",
        title = "Missing optional property",
        summary = "An optional property defined in the specification is missing"
    ),

    UNKNOWN_PROPERTY(
        id = "R2003",
        title = "Unknown property",
        summary = "A property was found that is not defined in the specification"
    ),

    /* ---------------- Composed Schema Rules ---------------- */
    DISCRIMINATOR_MISMATCH(
        id = "R3001",
        title = "Discriminator mismatch",
        summary = "The discriminator does not match the expected discriminator defined in the specification"
    ),

    INVALID_DISCRIMINATOR_SETUP(
        id = "R3002",
        title = "Invalid discriminator setup",
        summary = "The discriminator property defined in the specification is not present in the discriminator schemas"
    ),

    MISSING_DISCRIMINATOR(
        id = "R3003",
        title = "Missing discriminator",
        summary = "The discriminator property defined in the specification is missing"
    ),

    ANY_OF_UNKNOWN_KEY(
        id = "R3004",
        title = "Property not in any schema options",
        summary = "The property is not defined in any available schema options"
    ),

    ANY_OF_NO_MATCHING_SCHEMA(
        id = "R3005",
        title = "Property matches no schema option",
        summary = "The property does not satisfy any available schema options"
    ),

    ONE_OF_VALUE_MISMATCH(
        id = "R3006",
        title = "No matching schema option",
        summary = "The value does not satisfy the constraints of any available schema option"
    )
}
