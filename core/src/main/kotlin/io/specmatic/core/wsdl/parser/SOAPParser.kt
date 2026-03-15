package io.specmatic.core.wsdl.parser

import io.specmatic.core.ScenarioInfo
import io.specmatic.core.SpecmaticConfig

interface SOAPParser {
    fun convertToGherkin(url: String): String
    fun toScenarioInfos(url: String, specmaticConfig: SpecmaticConfig = SpecmaticConfig()): List<ScenarioInfo>
}
