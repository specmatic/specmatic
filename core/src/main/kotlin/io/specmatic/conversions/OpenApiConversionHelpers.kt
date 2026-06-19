package io.specmatic.conversions

internal fun toSpecmaticParamName(optional: Boolean, name: String) = when (optional) {
    true -> "${name}?"
    false -> name
}

internal fun escapeJsonPointer(token: String): String =
    token.replace("~", "~0").replace("/", "~1")
