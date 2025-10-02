package application

import io.mockk.every
import io.mockk.mockkStatic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.core.utilities.contractFilePathsFrom

internal class SpecmaticConfigTest {

    @Test
    fun `should return contractStubPathData`() {
        val contractPathData = ContractPathData("baseDir", "invalidPath")
        mockkStatic("io.specmatic.core.utilities.Utilities")
        every {
            contractFilePathsFrom(any(), any(), any(), any())
        }.returns(listOf(contractPathData))

        val paths = SpecmaticConfig().contractStubPathData()
        assertThat(paths == listOf(contractPathData)).isTrue
    }
}