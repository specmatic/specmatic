package application

import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import picocli.CommandLine
import io.specmatic.core.utilities.UncaughtExceptionHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.logging.LogManager
import kotlin.system.exitProcess

@SpringBootApplication
open class SpecmaticApplication {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            setupLogging()

            Thread.setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler())

            if (args.isEmpty() || args[0] !in listOf("--version", "-V")) {
                println("Specmatic Version: ${VersionProvider().getVersion()[0]}" + System.lineSeparator())
            }

            when {
                args.isEmpty() -> CommandLine(SpecmaticCommand()).usage(System.out)
                else ->  {
                    val app = SpringApplication(SpecmaticApplication::class.java)
                    app.setBannerMode(Banner.Mode.OFF)

                    exitProcess(SpringApplication.exit(app.run(*args)))
                }
            }
        }

        private fun setupLogging() {
            val logManager = LogManager.getLogManager()
            val props = Properties()
            props.setProperty("java.util.logging.ConsoleHandler.level", "FINE")
            val out = ByteArrayOutputStream(512)
            props.store(out, "No comment")
            logManager.readConfiguration(ByteArrayInputStream(out.toByteArray()))
        }
    }

}
