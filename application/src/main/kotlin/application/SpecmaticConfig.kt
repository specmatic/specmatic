package application

import io.specmatic.core.Configuration
import io.specmatic.core.DEFAULT_WORKING_DIRECTORY
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.core.utilities.contractFilePathsFrom

class SpecmaticConfig {
    fun contractStubPaths(useCurrentBranchForCentralRepo: Boolean = false): List<String> {
        return contractFilePathsFrom(
            Configuration.configFilePath, 
            DEFAULT_WORKING_DIRECTORY,
            { source -> source.stubContracts },
            useCurrentBranchForCentralRepo
        ).map { it.path }
    }

    fun contractTestPaths(useCurrentBranchForCentralRepo: Boolean = false): List<String> {
        return contractFilePathsFrom(
            Configuration.configFilePath,
            DEFAULT_WORKING_DIRECTORY,
            { source -> source.testContracts },
            useCurrentBranchForCentralRepo
        ).map { it.path }
    }

    fun contractStubPathData(useCurrentBranchForCentralRepo: Boolean = false): List<ContractPathData> {
        return contractFilePathsFrom(
            Configuration.configFilePath,
            DEFAULT_WORKING_DIRECTORY,
            { source -> source.stubContracts },
            useCurrentBranchForCentralRepo
        )
    }
}