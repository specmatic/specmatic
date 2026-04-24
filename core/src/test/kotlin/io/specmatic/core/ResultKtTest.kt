package io.specmatic.core

import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.trimmedLinesString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.function.Consumer

internal class ResultKtTest {
    private val scenario = object : ScenarioDetailsForResult {
        override val status: Int = 200
        override val ignoreFailure: Boolean = false
        override val name: String = "scenario"
        override val method: String = "GET"
        override val path: String = "/route"
        override fun testDescription(): String = "scenario description"
        override fun operationDescription(): String = "operation 1"

        override fun failureReportSubHeading(): String = "API: ${operationDescription()}"
    }

    private fun scenario(name: String, operationDescription: String) = object : ScenarioDetailsForResult {
        override val status: Int = 200
        override val ignoreFailure: Boolean = false
        override val name: String = name
        override val method: String = "GET"
        override val path: String = "/route"

        override fun testDescription(): String = "$name description"
        override fun operationDescription(): String = operationDescription
        override fun failureReportSubHeading(): String = "API: ${operationDescription()}"
    }

    @Test
    fun `add response body to result variables`() {
        val result = Success().withBindings(mapOf("data" to "response-body"), HttpResponse.ok("10")) as Success
        assertThat(result.variables).isEqualTo(mapOf("data" to "10"))
    }

    @Test
    fun `result report for failure with multiple causes`() {
        val result = Failure(
            causes = listOf(
                Result.FailureCause("", cause = Failure("Failure 1", breadCrumb = "id")),
                Result.FailureCause("", cause = Failure("Failure 2", breadCrumb = "height"))
            ), breadCrumb = "person"
        )

        assertThat(result.reportString()).satisfies(Consumer {
            assertThat(it).contains("person.id")
            assertThat(it).contains("person.height")
            assertThat(it).contains("Failure 1")
            assertThat(it).contains("Failure 2")

            println(result.reportString())
        })
    }

    @Test
    fun `failure copy operation should preserve metadata`() {
        val baseFailure = Failure(message = "error").updateScenario(scenario).updatePath("contract-path-failure")
        val transformedFailure = baseFailure.copy(breadCrumb = "REQUEST")
        assertMetadata(transformedFailure, scenario, "contract-path-failure")
    }

    @Test
    fun `failure nesting operations should preserve metadata`() {
        val baseFailure = Failure(message = "error").updateScenario(scenario).updatePath("contract-path-failure")
        assertMetadata(baseFailure.reason("nested"), scenario, "contract-path-failure")
        assertMetadata(baseFailure.breadCrumb("REQUEST"), scenario, "contract-path-failure")
    }

    @Test
    fun `success copy operation should preserve metadata`() {
        val baseSuccess = Success().updateScenario(scenario).updatePath("contract-path-success") as Success
        val transformedSuccess = baseSuccess.copy(variables = mapOf("x" to "1"))
        assertMetadata(transformedSuccess, scenario, "contract-path-success")
    }

    @Test
    fun `failure aggregation operation should not use metadata from one of the failures`() {
        val first = Failure("error-1").updateScenario(scenario).updatePath("contract-path-1")
        val second = Failure("error-2").updateScenario(scenario).updatePath("contract-path-2")
        val result = Result.fromFailures(listOf(first, second))
        assertThat(result.scenario).isNull()
        assertThat(result.contractPath).isNull()
    }

    @Test
    fun `empty results should be treated as failure in toResultIfAnyWithCausesOrFailure`() {
        val result = Results().toResultIfAnyWithCausesOrFailure()
        assertThat(result).isInstanceOf(Failure::class.java)
    }

    @Nested
    inner class DistinctReportTest {
        @Test
        fun `distinctReport should return empty string when there are only successes`() {
            val report = Results(listOf(Success())).distinctReport()

            assertThat(report).isEmpty()
        }

        @Test
        fun `distinctReport should return the default message when there are no results`() {
            val report = Results().distinctReport()

            assertThat(report).isEqualTo(PATH_NOT_RECOGNIZED_ERROR)
        }

        @Test
        fun `distinctReport should return the default message when only fluffy failures remain after filtering`() {
            val fluffyFailure = Failure("error", failureReason = FailureReason.URLPathMisMatch)

            val report = Results(listOf(fluffyFailure)).distinctReport()

            assertThat(report).isEqualTo(PATH_NOT_RECOGNIZED_ERROR)
        }

        @Test
        fun `distinctReport should return the default message when a fluffy failure is present alongside a success`() {
            val fluffyFailure = Failure("error", failureReason = FailureReason.URLPathMisMatch)

            val report = Results(listOf(Success(), fluffyFailure)).distinctReport()

            assertThat(report).isEqualTo(PATH_NOT_RECOGNIZED_ERROR)
        }

        @Test
        fun `distinctReport should merge failures with the same scenario and deduplicate match failure details`() {
            val timestampFailure = Failure("timestamp error", breadCrumb = "timestamp").updateScenario(scenario).updatePath("contract-path")
            val eventFailure = Failure("event error", breadCrumb = "event").updateScenario(scenario).updatePath("contract-path")
            val combinedFailure = Result.fromFailures(listOf(timestampFailure, eventFailure))
                .updateScenario(scenario)
                .updatePath("contract-path")

            val report = Results(
                listOf(
                    combinedFailure,
                    timestampFailure
                )
            ).distinctReport()

            assertThat(report.trimmedLinesString()).isEqualTo(
                """
            Error from contract contract-path

              In scenario "scenario"
              API: operation 1

                >> timestamp

                    timestamp error

                >> event

                    event error
            """.trimIndent().trimmedLinesString()
            )
        }

        @Test
        fun `distinctReport should render a single failure report as is`() {
            val timestampFailure = Failure("timestamp error", breadCrumb = "timestamp")
                .updateScenario(scenario)
                .updatePath("contract-path")

            val report = Results(listOf(timestampFailure)).distinctReport()

            assertThat(report.trimmedLinesString()).isEqualTo(
                """
            Error from contract contract-path

              In scenario "scenario"
              API: operation 1

                >> timestamp

                    timestamp error
            """.trimIndent().trimmedLinesString()
            )
        }

        @Test
        fun `distinctReport should merge failures with the same contract path when scenario is absent`() {
            val timestampFailure = Failure("timestamp error", breadCrumb = "timestamp").updatePath("contract-path")
            val eventFailure = Failure("event error", breadCrumb = "event").updatePath("contract-path")

            val report = Results(listOf(timestampFailure, eventFailure)).distinctReport()

            assertThat(report.trimmedLinesString()).isEqualTo(
                """
            Error from contract contract-path

              >> timestamp

                  timestamp error

              >> event

                  event error
            """.trimIndent().trimmedLinesString()
            )
        }

        @Test
        fun `distinctReport should merge failures with the same scenario when contract path is absent`() {
            val timestampFailure = Failure("timestamp error", breadCrumb = "timestamp").updateScenario(scenario)
            val eventFailure = Failure("event error", breadCrumb = "event").updateScenario(scenario)

            val report = Results(listOf(timestampFailure, eventFailure)).distinctReport()

            assertThat(report.trimmedLinesString()).isEqualTo(
                """
            In scenario "scenario"
            API: operation 1

              >> timestamp

                  timestamp error

              >> event

                  event error
            """.trimIndent().trimmedLinesString()
            )
        }

        @Test
        fun `distinctReport should merge failures when both contract path and scenario are absent`() {
            val timestampFailure = Failure("timestamp error", breadCrumb = "timestamp")
            val eventFailure = Failure("event error", breadCrumb = "event")

            val report = Results(listOf(timestampFailure, eventFailure)).distinctReport()

            assertThat(report.trimmedLinesString()).isEqualTo(
                """
            >> timestamp

               timestamp error

            >> event

               event error
            """.trimIndent().trimmedLinesString()
            )
        }

        @Test
        fun `distinctReport should not merge failures from different contract paths when scenario is the same`() {
            val sharedScenario = scenario(name = "shared scenario", operationDescription = "shared operation")
            val firstPathFailure = Failure("timestamp error", breadCrumb = "timestamp")
                .updateScenario(sharedScenario)
                .updatePath("contract-path-1")
            val secondPathFailure = Failure("event error", breadCrumb = "event")
                .updateScenario(sharedScenario)
                .updatePath("contract-path-2")

            val report = Results(listOf(firstPathFailure, secondPathFailure)).distinctReport()

            assertThat(report.trimmedLinesString()).isEqualTo(
                """
            Error from contract contract-path-1

              In scenario "shared scenario"
              API: shared operation

                >> timestamp

                    timestamp error

            Error from contract contract-path-2

              In scenario "shared scenario"
              API: shared operation

                >> event

                    event error
            """.trimIndent().trimmedLinesString()
            )
        }

        @Test
        fun `distinctReport should not merge failures from different scenarios while deduplicating the match failure details in one of the scenarios`() {
            val timestampFailure = Failure("timestamp error", breadCrumb = "timestamp").updateScenario(scenario).updatePath("contract-path")
            val eventFailure = Failure("event error", breadCrumb = "event").updateScenario(scenario).updatePath("contract-path")
            val combinedFailure = Result.fromFailures(listOf(timestampFailure, eventFailure))
                .updateScenario(scenario)
                .updatePath("contract-path")

            val otherScenario = scenario(name = "other scenario", operationDescription = "operation 2")
            val idFailure = Failure("id error", breadCrumb = "id").updateScenario(otherScenario).updatePath("contract-path")

            val report = Results(
                listOf(
                    combinedFailure,
                    timestampFailure,
                    idFailure
                )
            ).distinctReport()

            assertThat(report.trimmedLinesString()).isEqualTo(
                """
            Error from contract contract-path

              In scenario "scenario"
              API: operation 1

                >> timestamp

                    timestamp error

                >> event

                    event error
           
            Error from contract contract-path
            
              In scenario "other scenario"
              API: operation 2
            
                >> id
                
                    id error
            """.trimIndent().trimmedLinesString()
            )
        }
    }


    private fun assertMetadata(result: Result, scenario: ScenarioDetailsForResult?, contractPath: String?) {
        assertThat(result.scenario).isEqualTo(scenario)
        assertThat(result.contractPath).isEqualTo(contractPath)
    }
}
