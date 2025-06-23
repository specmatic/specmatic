package io.specmatic.core

import java.io.File

class PassThroughHook : Hook {
    override fun readContract(path: String): String {
        return checkExists(File(path)).readText()
    }
}
