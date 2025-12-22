package io.specmatic.core

object SuccessReport: Report {
    override fun toString(): String = toText()

    override fun toText(): String {
        return ""
    }

    override fun toIssues(breadCrumbToJsonPathConverter: BreadCrumbToJsonPathConverter): List<Issue> {
        return emptyList()
    }
}