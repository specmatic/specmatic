package io.specmatic.core

data class FailureReport(val contractPath: String?, private val scenarioMessage: String?, val scenario: ScenarioDetailsForResult?, private val matchFailureDetailList: List<MatchFailureDetails>): Report {
    fun errorMessage(): String {
        if(matchFailureDetailList.size != 1)
            return toText()
        return errorMessagesToString(matchFailureDetailList.first().errorMessages)
    }

    fun breadCrumbs(): String {
        if(matchFailureDetailList.size != 1)
            return ""

        return breadCrumbString(matchFailureDetailList.first().breadCrumbs)
    }

    override fun toText(): String {
        val contractLine = contractPathDetails()
        val scenarioDetails = scenarioDetails(scenario) ?: ""

        val matchFailureDetails = matchFailureDetails()

        val reportDetails: String = scenario?.let {
            "$scenarioDetails${System.lineSeparator()}${System.lineSeparator()}${matchFailureDetails.prependIndent("  ")}"
        } ?: matchFailureDetails

        val report = contractLine?.let {
            val reportIndent = if(contractLine.isNotEmpty()) "  " else ""
            "$contractLine${reportDetails.prependIndent(reportIndent)}"
        } ?: reportDetails

        return report.trimIndent()
    }

    override fun toString(): String = toText()

    private fun matchFailureDetails(): String {
        return matchFailureDetailList.sortedBy { it.isPartial }.joinToString("\n\n") {
            matchFailureDetails(it)
        }
    }

    private fun matchFailureDetails(matchFailureDetails: MatchFailureDetails): String {
        val (breadCrumbs, errorMessages) = matchFailureDetails

        val breadCrumbString = startOfBreadCrumbPrefix(breadCrumbString(breadCrumbs))

        val errorMessagesString = errorMessagesToString(errorMessages)

        return listOf(breadCrumbString, errorMessagesString.prependIndent("   ")).filter { it.isNotBlank() }.joinToString("${System.lineSeparator()}${System.lineSeparator()}")
    }

    private fun errorMessagesToString(errorMessages: List<String>) =
        errorMessages.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    private fun breadCrumbString(breadCrumbs: List<String>): String {
        return breadCrumbs
            .filter { it.isNotBlank() }
            .joinToString(".") { it.trim() }
            .replace(".(~~~", " (when ")
            .replace(Regex("^\\(~~~"), "(when ")
            .replace(".[", "[")
    }

    private fun startOfBreadCrumbPrefix(it: String) = when {
        it.isNotBlank() -> ">> $it"
        else -> ""
    }

    private fun contractPathDetails(): String? {
        if(contractPath.isNullOrBlank())
            return null

        return "Error from contract $contractPath\n\n"
    }

    private fun scenarioDetails(scenario: ScenarioDetailsForResult?): String? {
        return scenario?.let {
            val scenarioLine = """${scenarioMessage ?: "In scenario"} "${scenario.name}""""
            val urlLine =
                "API: ${scenario.method} ${scenario.path} -> ${scenario.status}"

            "$scenarioLine${System.lineSeparator()}$urlLine"
        }
    }
}
