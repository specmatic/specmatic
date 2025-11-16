package io.specmatic.stub

data class InterceptorError(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val hookType: String
) {
    override fun toString(): String {
        return buildString {
            appendLine("$hookType hook failed with exit code $exitCode")
            appendLine()

            // stdout section
            appendLine("stdout:")
            if (stdout.isNotBlank()) {
                appendLine(stdout.prependIndent("  "))
            } else {
                appendLine("  No content on stdout")
            }

            appendLine()

            // stderr section
            appendLine("stderr:")
            if (stderr.isNotBlank()) {
                appendLine(stderr.prependIndent("  "))
            } else {
                appendLine("  No content on stderr")
            }
        }.trimEnd()
    }
}
