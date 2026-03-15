package io.specmatic.core.log

class JSONFilePrinter(private val file: LogFile): LogPrinter {
    override fun print(msg: LogMessage, indentation: String) {
        file.appendText("${LogEnvelope.from(msg)}\n")
    }
}
