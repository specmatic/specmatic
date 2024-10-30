package io.specmatic.core

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.ExternalCommand
import java.io.File

enum class HookName {
    stub_load_contract,
    test_load_contract
}

class CommandHook(private val name: HookName): Hook {
    val command: String? = name.let {
        try {
            loadSpecmaticConfig().hooks[it.name]
        } catch (e: ContractException) {
            null
        }
    }

    override fun readContract(path: String): String {
        checkExists(File(path))

        return command?.let {
            logger.log("  Invoking hook $name when loading contract $path")
            ExternalCommand(it, ".", mapOf("CONTRACT_FILE" to path)).executeAsSeparateProcess()
        } ?: File(path).readText()
    }
}