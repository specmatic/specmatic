package io.specmatic.reports

class ReportGenerator<TInput, TReport>(
    private val strategy: ReportGeneratorStrategy<TInput>,
    private val createReport: (String?, List<ReportItem>) -> TReport
) {
    fun generate(configPath: String?, allInputs: List<TInput>, processedInputs: List<TInput>): TReport {
        val allByMeta = groupByMetadata(allInputs)
        val processedByMeta = groupByMetadata(processedInputs)
        val processedCountsByMeta = processedByMeta.mapValues { (_, items) ->
            items.groupingBy { it.hashCodeByIdentity() }.eachCount()
        }

        val items = allByMeta.map { (meta, allOps) ->
            val totalOps = combineByGroup(allOps)
            val processedGroups = processedByMeta[meta].orEmpty()
            val processedCounts = processedCountsByMeta[meta].orEmpty()
            val coverageStatusByGroup = processedGroups.associate { it.hashCodeByIdentity() to it.coverageStatus }
            val operations = totalOps.map { (groupIdentity, op) ->
                val count = processedCounts[groupIdentity] ?: 0
                val coverageStatus = coverageStatusByGroup[groupIdentity] ?: op.coverageStatus
                op.withCount(count).withCoverageStatus(coverageStatus)
            }

            ReportItem(meta, operations)
        }

        return createReport(configPath, items)
    }

    private fun groupByMetadata(inputs: List<TInput>): Map<ReportMetadata, List<OperationGroup>> {
        return inputs.groupingBy { strategy.toMetadata(it) }.fold(listOf()) { acc, input ->
            acc.plus(strategy.toOperationGroup(input))
        }
    }

    private fun combineByGroup(ops: List<OperationGroup>): Map<Int, OperationGroup> {
        return ops.groupingBy { it.hashCodeByIdentity() }.aggregate { _, acc, elem, _ ->
            acc?.let(elem::merge) ?: elem
        }
    }

    companion object {
        fun <T> createStubUsageReportGenerator(strategy: ReportGeneratorStrategy<T>) =
            ReportGenerator(strategy = strategy, createReport = ::StubUsageReport)

        fun <T> createTestCoverageReportGenerator(strategy: ReportGeneratorStrategy<T>) =
            ReportGenerator(strategy = strategy, createReport = ::TestCoverageReport)
    }
}
