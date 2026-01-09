package application

import io.specmatic.core.Feature
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.loadSpecmaticConfigOrDefault
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.SpecmaticConfigSource
import io.specmatic.stub.loadContractStubsFromFiles
import io.specmatic.stub.loadContractStubsFromImplicitPaths
import java.io.File

class StubLoaderEngine {
    fun loadStubs(
        contractPathDataList: List<ContractPathData>,
        dataDirs: List<String>,
        specmaticConfigSource: SpecmaticConfigSource,
        strictMode: Boolean
    ): List<Pair<Feature, List<ScenarioStub>>> {
        contractPathDataList.forEach { contractPath ->
            if(!File(contractPath.path).exists()) {
                logger.log("$contractPath does not exist.")
            }
        }

        val specmaticConfig = specmaticConfigSource.load().config

        return when {
            dataDirs.isNotEmpty() -> {
                loadContractStubsFromFiles(contractPathDataList, dataDirs, specmaticConfig, strictMode, withImplicitStubs = true)
            }
            else -> loadContractStubsFromImplicitPaths(contractPathDataList, specmaticConfig, dataDirs)
        }
    }
}