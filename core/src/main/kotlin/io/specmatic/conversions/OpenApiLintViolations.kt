package io.specmatic.conversions

import io.specmatic.core.RuleViolation

enum class OpenApiLintViolations(override val id: String, override val title: String, override val summary: String? = null): RuleViolation {
    /* -------- Length constraints -------- */
    INVALID_MIN_LENGTH(
        id = "OAS0001",
        title = "Invalid min length",
        summary = "Minimum length must be a positive integer"
    ),

    INVALID_MAX_LENGTH(
        id = "OAS0002",
        title = "Invalid max length",
        summary = "Maximum length must be greater than or equal to minimum length"
    ),

    LENGTH_EXCEEDS_LIMIT(
        id = "OAS0003",
        title = "Excessive length",
        summary = "Length should not exceed recommended maximum of 4MB"
    ),

    PATTERN_LENGTH_INCOMPATIBLE(
        id = "OAS0004",
        title = "Pattern length conflict",
        summary = "Pattern must be able to generate values matching the minimum and maximum length"
    ),

    /* -------- Numeric constraints -------- */
    INVALID_NUMERIC_BOUNDS(
        id = "OAS0005",
        title = "Invalid numeric bounds",
        summary = "Maximum must be greater than or equal to minimum"
    ),

    /* -------- Parameters -------- */
    INVALID_PARAMETER_DEFINITION(
        id = "OAS0010",
        title = "Invalid parameter definition",
        summary = "Parameters must define required properties such as 'name', 'schema', etc."
    ),

    PATH_PARAMETER_MISSING(
        id = "OAS0011",
        title = "Missing path parameter",
        summary = "All path template segments must be defined as parameters"
    ),

    /* -------- Security -------- */
    SECURITY_PROPERTY_REDEFINED(
        id = "OAS0020",
        title = "Security property redefined",
        summary = "Security scheme properties should not be redefined in parameters"
    ),

    SECURITY_SCHEME_MISSING(
        id = "OAS0021",
        title = "Security scheme missing",
        summary = "Referenced security schemes must be defined and resolve-able"
    ),

    /* -------- Media type & responses -------- */
    MEDIA_TYPE_OVERRIDDEN(
        id = "OAS0030",
        title = "Media type overridden",
        summary = "Media types should not be overridden by Content-Type parameters"
    ),

    INVALID_OPERATION_STATUS(
        id = "OAS0031",
        title = "Invalid response status",
        summary = "Response status must be a valid integer or literal default"
    ),

    /* -------- Schema & references -------- */
    UNRESOLVED_REFERENCE(
        id = "OAS0041",
        title = "Unresolved reference",
        summary = "References must resolve to a valid reusable component"
    ),

    REF_HAS_SIBLINGS(
        id = "OAS0042",
        title = "Invalid \$ref usage",
        summary = "A \$ref should not define sibling properties as per OAS 3.0 standards"
    ),

    SCHEMA_UNCLEAR(
        id = "OAS0043",
        title = "Unclear schema",
        summary = "The intent of this schema is unclear or may not be supported. Consider reaching out if this is an issue"
    ),

    /* -------- Others -------- */
    UNSUPPORTED_FEATURE(
        id = "OAS9999",
        title = "Unsupported feature",
        summary = "This feature is currently not yet supported, consider reaching out if you would like us to prioritise the support"
    ),
}
