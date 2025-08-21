package io.specmatic.test.reports

import io.specmatic.core.ReportConfiguration
import io.specmatic.core.SpecmaticConfig
import io.specmatic.test.reports.renderers.ReportRenderer

interface ReportProcessor<T> {
    fun process(specmaticConfig: SpecmaticConfig)

    fun configureReportRenderers(reportConfiguration: ReportConfiguration): List<ReportRenderer<T>>

    fun assertSuccessCriteria(reportConfiguration: ReportConfiguration, report: T)
}
