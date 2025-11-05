package io.specmatic.core

import io.specmatic.test.asserts.WILDCARD_INDEX

@JvmInline
value class BreadCrumb(val value: String) {
    companion object {
        val REQUEST = BreadCrumb("REQUEST")
        val RESPONSE = BreadCrumb("RESPONSE")
        val BODY = BreadCrumb("BODY")
        val PATH = BreadCrumb("PATH")
        val HEADER = BreadCrumb("HEADER")
        val QUERY = BreadCrumb("QUERY")
        val PARAMETERS = BreadCrumb("PARAMETERS")
        val PARAM_PATH = PARAMETERS.plus(PATH)
        val PARAM_HEADER = PARAMETERS.plus(HEADER)
        val PARAM_QUERY = PARAMETERS.plus(QUERY)
        val SOAP_ACTION = BreadCrumb("SOAPAction")

        private fun combine(vararg breadCrumbs: String): String {
            val breadCrumbsToCombine = breadCrumbs.filterNot(String::isBlank).ifEmpty { return "" }
            return breadCrumbsToCombine.reduce { acc, breadCrumb ->
                if (breadCrumb == WILDCARD_INDEX) return@reduce "$acc$breadCrumb"
                if (breadCrumb.toIntOrNull() != null) return@reduce "$acc[$breadCrumb]"
                "$acc.$breadCrumb"
            }
        }
    }

    override fun toString(): String = value

    fun equalTo(string: String, ignoreCase: Boolean = false) = value.equals(string, ignoreCase)

    fun with(key: String?): String = if (key == null) value else combine(value, key)

    fun plus(key: String?): BreadCrumb = if (key == null) this else BreadCrumb(combine(value, key))

    fun plus(other: BreadCrumb): BreadCrumb = BreadCrumb(combine(value, other.value))

    fun previous(): BreadCrumb {
        val bounds = lastSegmentBounds()
        if (bounds?.first == 0) return BreadCrumb("")
        return BreadCrumb(bounds?.let { value.take(bounds.first.dec()) }.orEmpty())
    }

    fun last(): BreadCrumb {
        val bounds = lastSegmentBounds()
        return BreadCrumb(if (bounds != null) value.substring(bounds) else "")
    }

    private fun lastSegmentBounds(): IntRange? {
        val dotIndex = value.lastIndexOf('.')
        if (dotIndex != -1) return (dotIndex + 1) until value.length

        val bracketIndex = value.lastIndexOf('[')
        if (bracketIndex != -1 && value.endsWith(']')) return bracketIndex until value.length

        return if (value.isNotEmpty()) return 0 until value.length else null
    }
}
