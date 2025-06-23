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
            if (breadCrumbs.isEmpty()) return ""
            return breadCrumbs.reduce { acc, breadCrumb ->
                if (breadCrumb == WILDCARD_INDEX) "$acc$breadCrumb"
                else "$acc.$breadCrumb"
            }
        }
    }

    fun with(key: String?): String = if (key == null) value else combine(value, key)

    fun plus(key: String?): BreadCrumb = if (key == null) this else BreadCrumb(combine(value, key))

    fun plus(other: BreadCrumb): BreadCrumb = BreadCrumb(combine(value, other.value))
}
