package application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine.Command
import java.util.concurrent.Callable

class ConfigCommandProviderTest {
    @Test
    fun `loads contributed config subcommands`() {
        val commandLine = ConfigCommand.commandLine(listOf(TestConfigCommandProvider()))

        assertThat(commandLine.subcommands.keys).containsExactly("upgrade", "validate", "generated-for-test")
    }

    @Test
    fun `does not expose enterprise config commands without a provider`() {
        val commandLine = ConfigCommand.commandLine()

        assertThat(commandLine.subcommands.keys).containsExactly("upgrade", "validate")
    }
}

private class TestConfigCommandProvider : ConfigCommandProvider {
    override fun subcommands(): List<Any> = listOf(GeneratedForTestCommand())
}

@Command(name = "generated-for-test")
private class GeneratedForTestCommand : Callable<Int> {
    override fun call(): Int = 0
}
