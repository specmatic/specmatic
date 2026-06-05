package io.specmatic.conversions

internal data class ExternalSourceProjection(
    val sourceFile: String,
    val sourceBasePointer: String,
    val targetPointer: String,
) {
    fun contains(file: String, pointer: String): Boolean {
        if (file != sourceFile) return false
        if (sourceBasePointer.isEmpty()) return true
        return pointer == sourceBasePointer || pointer.startsWith("$sourceBasePointer/")
    }

    fun targetPointerFor(sourcePointer: String): String =
        if (sourceBasePointer.isEmpty())
            "$targetPointer$sourcePointer"
        else
            "$targetPointer${sourcePointer.removePrefix(sourceBasePointer)}"
}
