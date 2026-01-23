package io.specmatic.core.pattern

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import io.specmatic.core.ContractCache
import io.specmatic.core.ContractFileWithExports
import io.specmatic.core.References
import io.mockk.every
import io.mockk.mockk

internal class RowTest {
    @Test
    fun `a row returns the value of the specified variable`() {
        val row = Row(mapOf("name" to "(${DEREFERENCE_PREFIX}name)"), variables = mapOf("name" to "Jane"))
        assertThat(row.getField("name")).isEqualTo("Jane")
    }

    @Test
    fun `if the specified variable is missing the row throws an exception`() {
        val row = Row(mapOf("name" to "(${DEREFERENCE_PREFIX}name)"))
        assertThatThrownBy { row.getField("name") }.isInstanceOf(ContractException::class.java)
    }

    @Test
    fun `returns the value returned from another contract`() {
        val data = mapOf("name" to "Jane")

        val contractFile = mockk<ContractFileWithExports>()
        every {
            contractFile.runContractAndExtractExports(any(), any(), any())
        }.returns(data)
        val contractFileName = "path.spec"
        every {
            contractFile.absolutePath
        }.returns(contractFileName)

        val contractCache = ContractCache(mutableMapOf(contractFileName to data))

        val references = References("user", contractFile, contractCache = contractCache)
        val row = Row(mapOf("name" to "(${DEREFERENCE_PREFIX}user.name)"), references = mapOf("user" to references))

        assertThat(row.getField("name")).isEqualTo("Jane")
    }

    @Test
    fun `returns a plain value if present`() {
        val row = Row(mapOf("name" to "Jane"))
        assertThat(row.getField("name")).isEqualTo("Jane")
    }
}