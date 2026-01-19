package io.specmatic.conversions

import io.specmatic.core.RuleViolation

enum class OpenApiLintViolations(override val id: String, override val title: String, override val summary: String? = null): RuleViolation {
    INVALID_PARAMETER_DEFINITION(
        id = "OAS0001",
        title = "Invalid parameter definition",
        summary = "Parameters must define required properties such as 'name', 'schema', etc."
    ),

    SECURITY_PROPERTY_REDEFINED(
        id = "OAS0002",
        title = "Security property redefined",
        summary = "Security scheme properties should not be redefined in parameters"
    ),

    INVALID_OPERATION_STATUS(
        id = "OAS0003",
        title = "Invalid response status",
        summary = "Response status must be a valid integer or literal default"
    ),

    PATH_PARAMETER_MISSING(
        id = "OAS0004",
        title = "Missing path parameter",
        summary = "All path template segments must be defined as parameters"
    ),

    SECURITY_SCHEME_MISSING(
        id = "OAS0005",
        title = "Security scheme missing",
        summary = "Referenced security schemes must be defined and resolve-able"
    ),

    MEDIA_TYPE_OVERRIDDEN(
        id = "OAS0006",
        title = "Media type overridden",
        summary = "Media types should not be overridden by Content-Type parameters"
    ),

    UNRESOLVED_REFERENCE(
        id = "OAS0007",
        title = "Unresolved reference",
        summary = "References must resolve to a valid reusable component"
    ),

    REF_HAS_SIBLINGS(
        id = "OAS0008",
        title = "Invalid \$ref usage",
        summary = "A \$ref should not define sibling properties as per OAS 3.0 standards"
    ),

    SCHEMA_UNCLEAR(
        id = "OAS0009",
        title = "Unclear schema",
        summary = "The intent of this schema is unclear or may not be supported. Consider reaching out if this is an issue"
    ),

    UNSUPPORTED_FEATURE(
        id = "OAS9999",
        title = "Unsupported feature",
        summary = "This feature is currently not yet supported, consider reaching out if you would like us to prioritise the support"
    ),
}
