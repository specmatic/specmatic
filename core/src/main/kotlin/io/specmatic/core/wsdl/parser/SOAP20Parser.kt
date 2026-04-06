package io.specmatic.core.wsdl.parser

import io.specmatic.core.ScenarioInfo
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.pattern.ContractException

class SOAP20Parser : SOAPParser {
    override fun convertToGherkin(url: String): String {
        TODO("SOAP 2.0 is not yet implemented")
    }

    override fun toScenarioInfos(url: String, specmaticConfig: SpecmaticConfig): List<ScenarioInfo> {
        throw ContractException("SOAP 1.2 direct WSDL execution is not supported yet")
    }
}
