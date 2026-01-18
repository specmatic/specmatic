package io.specmatic.conversions

import io.specmatic.core.RuleViolation

enum class OpenApiLintViolations(override val id: String, override val title: String, override val summary: String? = null): RuleViolation {
    /* -------- Length constraints -------- */
    INVALID_MIN_LENGTH(
        id = "S0004",
        title = "Invalid min length",
        summary = "minLength should never be less than 0, but it is -10. Please use a positive minLength, or drop the constraint."
    ),

    INVALID_MAX_LENGTH(
        id = "S0002",
        title = "Invalid max length",
        summary = "maxLength 10 should have been greater than minLength 20. Please make sure that maxLength and minLength are not in conflict."
    ),

    LENGTH_EXCEEDS_LIMIT(
        id = "S0003",
        title = "Excessive length",
        summary = "A length of 2GB is impractical. Limiting the maxLength for now to the more practical 4MB, which is enough for most purposes. Please double-check the maxLength needed for this value and adjust accordingly."
    ),

    PATTERN_LENGTH_INCOMPATIBLE(
        id = "S0002",
        title = "Pattern length conflict",
        summary = "The regex pattern \"a{20}\" is incompatible with maxLength 10. Either remove maxLength, or ensure that it is greater than the largest possible regex."
    ),

    /* -------- Numeric constraints -------- */
    INVALID_NUMERIC_BOUNDS(
        id = "S0002",
        title = "Invalid numeric bounds",
        summary = "maximum 10 should have been greater than minimum 20. Please make sure that maximum and minimum are not in conflict."
    ),

    /* -------- Parameters -------- */
    INVALID_PARAMETER_DEFINITION(
        id = "OAS0001",
        title = "Invalid parameter definition",
        summary = "\"name\" is mandatory for parameters, but it was missing. It will be ignored by Specmatic. Please add a name, or remove the parameter."
    ),

    PATH_PARAMETER_MISSING(
        id = "OAS0004",
        title = "Missing path parameter",
        summary = "The path parameter named \"abc\" was declared, but no path parameter definition for \"abc\" was found. Please add a definition for \"abc\" to the spec."
    ),

    /* -------- Security -------- */
    SECURITY_PROPERTY_REDEFINED(
        id = "OAS0002",
        title = "Security property redefined",
        summary = "The header/query param named \"xyz\" for security scheme named \"pqr\" was explicitly re-defined as a parameter. The parameter will be ignored, and should be removed."
    ),

    SECURITY_SCHEME_MISSING(
        id = "OAS0005",
        title = "Security scheme missing",
        summary = "Security scheme named \"abc\" was used, but no such security scheme has been defined in the spec. Either drop the security scheme, or add a definition to the spec."
    ),

    /* -------- Media type & responses -------- */
    MEDIA_TYPE_OVERRIDDEN(
        id = "OAS0006",
        title = "Media type overridden",
        summary = "Content-Type was declared in the spec, and will be ignored. Please remove it."
    ),

    INVALID_OPERATION_STATUS(
        id = "OAS0003",
        title = "Invalid response status",
        summary = "The response status code must be a valid integer, or the string value \"default\", but was \"abc\". Please use a valid status code or remove the status code section."
    ),

    /* -------- Schema & references -------- */
    UNRESOLVED_REFERENCE(
        id = "S0001",
        title = "Unresolved reference",
        summary = "The element referred to at this path was not found in the spec. Please add the missing header/schema/etc named \"xyz\"."
    ),

    REF_HAS_SIBLINGS(
        id = "OAS0008",
        title = "Invalid \$ref usage",
        summary = "This reference has sibling properties. In accordance with the OpenAPI 3.0 standard, they will be ignored. Please remove them."
    ),

    SCHEMA_UNCLEAR(
        id = "OAS0043",
        title = "Unclear schema",
        summary = "Could not recognize type \"unknown-type\". Please share this error with the Specmatic team."
    ),

    /* -------- Others -------- */
    UNSUPPORTED_FEATURE(
        id = "OAS9999",
        title = "Unsupported feature",
        summary = "Specmatic does not currently support this feature. Please reach out to the Specmatic team if you need support for this feature."
    ),
}
