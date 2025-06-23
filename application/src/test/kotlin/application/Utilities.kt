package application

import java.io.ByteArrayOutputStream
import java.io.PrintStream

fun <ReturnType> captureStandardOutput(redirectStdErrToStdout: Boolean = false, fn: () -> ReturnType): Pair<String, ReturnType> {
    val originalErr = System.err
    val originalOut = System.out

    val byteArrayOutputStreamOut = ByteArrayOutputStream()
    val newOut = PrintStream(byteArrayOutputStreamOut)
    System.setOut(newOut)

    if (redirectStdErrToStdout) {
        System.setErr(newOut)
    }

    val result = try {
        fn()
    } finally {
        newOut.flush()

        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    return Pair(String(byteArrayOutputStreamOut.toByteArray()).trim(), result)
}
