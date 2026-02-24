package io.specmatic.test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ContractTestSettingsTest {
    private val filterPropertyKey = "filter"
    private val originalFilterValue = System.getProperty(filterPropertyKey)

    @AfterEach
    fun restoreFilterProperty() {
        if (originalFilterValue != null) {
            System.setProperty(filterPropertyKey, originalFilterValue)
        } else {
            System.clearProperty(filterPropertyKey)
        }
    }

    @Test
    fun `getReportFilter returns config filter when override matches`() {
        System.setProperty(filterPropertyKey, "PATH='/config'")
        val settings = ContractTestSettings(filter = "PATH='/config'")
        assertThat(settings.getReportFilter()).isEqualTo("PATH='/config'")
    }

    @Test
    fun `getReportFilter combines config and override filters when they differ`() {
        System.setProperty(filterPropertyKey, "PATH='/config'")
        val settings = ContractTestSettings(filter = "STATUS='200'")
        assertThat(settings.getReportFilter()).isEqualTo("( PATH='/config' ) && ( STATUS='200' )")
    }

    @Test
    fun `getReportFilter uses override when config filter is missing`() {
        System.clearProperty(filterPropertyKey)
        val settings = ContractTestSettings(filter = "STATUS='200'")
        assertThat(settings.getReportFilter()).isEqualTo("STATUS='200'")
    }

    @Test
    fun `getReportFilter uses config filter when override is missing`() {
        System.setProperty(filterPropertyKey, "PATH='/config'")
        val settings = ContractTestSettings(filter = null)
        assertThat(settings.getReportFilter()).isEqualTo("PATH='/config'")
    }
}
