package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.StringValue
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.ContractTest
import io.specmatic.test.ScenarioAsTest
import io.specmatic.test.TestExecutionReason
import io.specmatic.test.TestExecutor
import io.specmatic.test.TestSkipReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FeatureDecisionMatrixTest {
    private fun featureFromResourceOpenapi(resource: String): Feature {
        return OpenApiSpecification.fromFile("openapi/$resource").toFeature()
    }

    private fun Scenario.matchesOperationId(other: Scenario): Boolean = this.operationMetadata?.operationId == other.operationMetadata?.operationId
    private fun firstScenario(feature: Feature, status: Int, operationId: String? = null, hasExamples: Boolean? = null): Scenario {
        return feature.scenarios.first {
            it.httpResponsePattern.status == status &&
            (operationId == null || it.operationMetadata?.operationId == operationId) &&
            (hasExamples == null || it.hasExamples() == hasExamples)
        }
    }

    @Nested
    inner class ResiliencyModeTests {
        @Test
        fun `resiliency null should not generate negative scenarios and generative positive scenarios when no examples exist`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml")
            val scenario200 = firstScenario(feature, 200, hasExamples = false)
            val scenario400 = firstScenario(feature, 400, hasExamples = false)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario200, scenario400),
                scenarios = sequenceOf(Decision.execute(scenario200), Decision.execute(scenario400))
            ).toList()

            assertThat(generated).hasSize(3)
            val badRequest = generated.filter { it.context.isA4xxScenario() }
            assertThat(badRequest).hasSize(1).allSatisfy {
                assertThat(it).isInstanceOf(Decision.Skip::class.java)
                assertThat(it.reasoning.mainReason).isEqualTo(TestSkipReason.GENERATIVE_DISABLED)
                assertThat(it.reasoning.otherReasons).containsExactly(TestSkipReason.EXAMPLES_REQUIRED)
            }

            val successRequest = generated.filter { it.context.isA2xxScenario() }
            assertThat(successRequest).hasSize(2).allSatisfy {
                assertThat(it).isInstanceOf(Decision.Execute::class.java)
                assertThat(it.reasoning.mainReason).isEqualTo(TestExecutionReason.NO_EXAMPLE)
                assertThat(it.reasoning.otherReasons).isEmpty()
            }
        }

        @Test
        fun `resiliency none should not generate negative scenarios and not generative positive scenarios when examples exist`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml").disableGenerativeTesting()
            val scenario200 = firstScenario(feature, 200, hasExamples = true)
            val scenario400 = firstScenario(feature, 400, hasExamples = false)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario200, scenario400),
                scenarios = sequenceOf(Decision.execute(scenario200), Decision.execute(scenario400))
            ).toList()

            assertThat(generated).hasSize(2)
            val badRequest = generated.filter { it.context.isA4xxScenario() }
            assertThat(badRequest).hasSize(1).allSatisfy {
                assertThat(it).isInstanceOf(Decision.Skip::class.java)
                assertThat(it.reasoning.mainReason).isEqualTo(TestSkipReason.GENERATIVE_DISABLED)
                assertThat(it.reasoning.otherReasons).containsExactly(TestSkipReason.EXAMPLES_REQUIRED)
            }

            val successRequest = generated.filter { it.context.isA2xxScenario() }
            assertThat(successRequest).hasSize(1).allSatisfy {
                assertThat(it).isInstanceOf(Decision.Execute::class.java)
                assertThat(it.reasoning.mainReason).isEqualTo(TestExecutionReason.HAS_EXAMPLE)
                assertThat(it.reasoning.otherReasons).isEmpty()
            }
        }

        @Test
        fun `resiliency all should generate negative scenarios and positive scenarios`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml").enableGenerativeTesting()
            val scenario200 = firstScenario(feature, 200, hasExamples = false)
            val scenario400 = firstScenario(feature, 400, hasExamples = false)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario200, scenario400),
                scenarios = sequenceOf(Decision.execute(scenario200), Decision.execute(scenario400))
            ).toList()

            assertThat(generated).hasSize(6)
            val badRequest = generated.filter { it.context.isNegative }
            assertThat(badRequest).hasSize(4).allSatisfy {
                assertThat(it).isInstanceOf(Decision.Execute::class.java)
                assertThat(it.reasoning.mainReason).isEqualTo(TestExecutionReason.NEGATIVE_GENERATION_ENABLED)
                assertThat(it.reasoning.otherReasons).isEmpty()
            }

            val successRequest = generated.filter { !it.context.isNegative }
            assertThat(successRequest).hasSize(2).allSatisfy {
                assertThat(it).isInstanceOf(Decision.Execute::class.java)
                assertThat(it.reasoning.mainReason).isEqualTo(TestExecutionReason.POSITIVE_GENERATION_ENABLED)
                assertThat(it.reasoning.otherReasons).isEmpty()
            }
        }

        @Test
        fun `resiliency positive only should generate positive scenarios when no examples exist`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml").enableGenerativeTesting(onlyPositive = true)
            val scenario200 = firstScenario(feature, 200, hasExamples = false)
            val scenario400 = firstScenario(feature, 400, hasExamples = false)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario200, scenario400),
                scenarios = sequenceOf(Decision.execute(scenario200), Decision.execute(scenario400))
            ).toList()

            assertThat(generated).hasSize(3)
            val badRequest = generated.filter { it.context.isA4xxScenario() }
            assertThat(badRequest).hasSize(1).allSatisfy {
                assertThat(it).isInstanceOf(Decision.Skip::class.java)
                assertThat(it.reasoning.mainReason).isEqualTo(TestSkipReason.GENERATIVE_DISABLED)
                assertThat(it.reasoning.otherReasons).containsExactly(TestSkipReason.EXAMPLES_REQUIRED)
            }

            val successRequest = generated.filter { it.context.isA2xxScenario() }
            assertThat(successRequest).hasSize(2).allSatisfy {
                assertThat(it).isInstanceOf(Decision.Execute::class.java)
                assertThat(it.reasoning.mainReason).isEqualTo(TestExecutionReason.POSITIVE_GENERATION_ENABLED)
                assertThat(it.reasoning.otherReasons).isEmpty()
            }
        }

        @Test
        fun `resiliency positive only should generate positive scenarios when examples exist marking them appropriately`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml").enableGenerativeTesting(onlyPositive = true)
            val scenario200 = firstScenario(feature, 200, hasExamples = true)
            val scenario400 = firstScenario(feature, 400, hasExamples = false)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario200, scenario400),
                scenarios = sequenceOf(Decision.execute(scenario200), Decision.execute(scenario400))
            ).toList()

            assertThat(generated).hasSize(3)
            val badRequest = generated.filter { it.context.isA4xxScenario() }
            assertThat(badRequest).hasSize(1).allSatisfy {
                assertThat(it).isInstanceOf(Decision.Skip::class.java)
                assertThat(it.reasoning.mainReason).isEqualTo(TestSkipReason.GENERATIVE_DISABLED)
                assertThat(it.reasoning.otherReasons).containsExactly(TestSkipReason.EXAMPLES_REQUIRED)
            }

            val successRequest = generated.filter { it.context.isA2xxScenario() }
            val (example, positiveGeneration) = successRequest
            assertThat(successRequest).hasSize(2)
            assertThat(example).isInstanceOf(Decision.Execute::class.java)
            assertThat(example.reasoning.mainReason).isEqualTo(TestExecutionReason.HAS_EXAMPLE)
            assertThat(example.reasoning.otherReasons).isEmpty()

            assertThat(positiveGeneration).isInstanceOf(Decision.Execute::class.java)
            assertThat(positiveGeneration.reasoning.mainReason).isEqualTo(TestExecutionReason.POSITIVE_GENERATION_ENABLED)
            assertThat(positiveGeneration.reasoning.otherReasons).containsExactly(TestExecutionReason.HAS_EXAMPLE)
        }
    }

    @Nested
    inner class StrictModeTests {
        @Test
        fun `strict mode should skip 2xx scenarios without examples with strict violation`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml").copy(strictMode = true)
            val scenario = firstScenario(feature, 200, hasExamples = false)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario),
                scenarios = sequenceOf(Decision.execute(scenario))
            ).toList()

            assertThat(generated).hasSize(1)
            val skip = generated.single() as Decision.Skip
            assertThat(skip.reasoning.mainReason).isEqualTo(TestSkipReason.noExamples2xxAnd400(true))
            assertThat(skip.reasoning.otherReasons).isEmpty()
        }

        @Test
        fun `strict mode with resiliency none should mark 400 without examples as generative disabled with strict detail`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml").copy(strictMode = true).disableGenerativeTesting()
            val scenario = firstScenario(feature, 400, hasExamples = false)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario),
                scenarios = sequenceOf(Decision.execute(scenario))
            ).toList()

            assertThat(generated).hasSize(1)
            val skip = generated.single() as Decision.Skip<*>
            assertThat(skip.reasoning.mainReason).isEqualTo(TestSkipReason.GENERATIVE_DISABLED)
            assertThat(skip.reasoning.otherReasons).containsExactly(TestSkipReason.noExamples2xxAnd400(true))
        }

        @Test
        fun `strict mode should keep non-2xx non-400 no-example skips as no-examples violation`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml").copy(strictMode = true)
            val scenario = firstScenario(feature, 500, hasExamples = false)
            val secondScenario = firstScenario(feature, 404, hasExamples = false)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario, secondScenario),
                scenarios = sequenceOf(Decision.execute(scenario), Decision.execute(secondScenario))
            ).toList()

            assertThat(generated).hasSize(2).allSatisfy {
                assertThat(it).isInstanceOf(Decision.Skip::class.java); it as Decision.Skip
                assertThat(it.reasoning.mainReason).isEqualTo(TestSkipReason.noExamplesNon2xxAndNon400())
                assertThat(it.reasoning.otherReasons).isEmpty()
            }
        }
    }

    @Nested
    inner class PositiveScenarioTests {
        @Test
        fun `example-backed and schema-backed 2xx scenarios should execute with explicit reasoning`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml").disableGenerativeTesting()
            val exampleScenario = firstScenario(feature, 200, hasExamples = true)
            val schemaScenario = firstScenario(feature, 200, hasExamples = false)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(exampleScenario, schemaScenario),
                scenarios = sequenceOf(Decision.execute(exampleScenario), Decision.execute(schemaScenario))
            ).toList()

            assertThat(generated).hasSize(3).allSatisfy { decision ->
                assertThat(decision).isInstanceOf(Decision.Execute::class.java)
                assertThat(decision.context.isNegative).isFalse()
            }

            val exampleDecision = generated.single { it.context.matchesOperationId(exampleScenario) }
            assertThat(exampleDecision).isInstanceOf(Decision.Execute::class.java); exampleDecision as Decision.Execute
            assertThat(exampleDecision.value.value.generatedFrom).isEqualTo(GeneratedScenarioOrigin.EXAMPLE_ROW)
            assertThat(exampleDecision.reasoning.mainReason).isEqualTo(TestExecutionReason.HAS_EXAMPLE)
            assertThat(exampleDecision.reasoning.otherReasons).isEmpty()

            val schemaDecisions = generated.filter { it.context.matchesOperationId(schemaScenario) }
            assertThat(schemaDecisions).hasSize(2).allSatisfy { schemaDecision ->
                assertThat(schemaDecision).isInstanceOf(Decision.Execute::class.java); schemaDecision as Decision.Execute
                assertThat(schemaDecision.value.value.generatedFrom).isEqualTo(GeneratedScenarioOrigin.MUTATION)
                assertThat(schemaDecision.reasoning.mainReason).isEqualTo(TestExecutionReason.NO_EXAMPLE)
                assertThat(schemaDecision.reasoning.otherReasons).isEmpty()
            }
        }

        @Test
        fun `example-backed 400 scenarios should execute with has-example reasoning`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml")
            val scenario = firstScenario(feature, 400, hasExamples = true)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario),
                scenarios = sequenceOf(Decision.execute(scenario))
            ).toList()

            assertThat(generated).hasSize(1)
            val decision = generated.single() as Decision.Execute
            assertThat(decision.value.value.generatedFrom).isEqualTo(GeneratedScenarioOrigin.EXAMPLE_ROW)
            assertThat(decision.reasoning.mainReason).isEqualTo(TestExecutionReason.HAS_EXAMPLE)
            assertThat(decision.reasoning.otherReasons).isEmpty()
        }

        @Test
        fun `example-backed 404 scenarios should execute with has-example reasoning`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml")
            val scenario = firstScenario(feature, 404, hasExamples = true)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario),
                scenarios = sequenceOf(Decision.execute(scenario))
            ).toList()

            assertThat(generated).hasSize(1)
            val decision = generated.single() as Decision.Execute
            assertThat(decision.value.value.generatedFrom).isEqualTo(GeneratedScenarioOrigin.EXAMPLE_ROW)
            assertThat(decision.reasoning.mainReason).isEqualTo(TestExecutionReason.HAS_EXAMPLE)
            assertThat(decision.reasoning.otherReasons).isEmpty()
        }

        @Test
        fun `example-backed 500 scenarios should execute with has-example reasoning`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml")
            val scenario = firstScenario(feature, 500, hasExamples = true)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario),
                scenarios = sequenceOf(Decision.execute(scenario))
            ).toList()

            assertThat(generated).hasSize(1)
            val decision = generated.filterIsInstance<Decision.Execute<ReturnValue<Scenario>, Scenario>>().first()
            assertThat(decision.value.value.generatedFrom).isEqualTo(GeneratedScenarioOrigin.EXAMPLE_ROW)
            assertThat(decision.reasoning.mainReason).isEqualTo(TestExecutionReason.HAS_EXAMPLE)
            assertThat(decision.reasoning.otherReasons).isEmpty()
        }

        @Test
        fun `non-2xx non-400 scenarios without examples should be skipped with no-examples violation`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml")
            val scenario = firstScenario(feature, 500, hasExamples = false)
            val secondScenario = firstScenario(feature, 404, hasExamples = false)
            val generated = feature.generateContractTestScenariosWithDecision(
                originalScenarios = listOf(scenario, secondScenario),
                scenarios = sequenceOf(Decision.execute(scenario), Decision.execute(secondScenario))
            ).toList()

            assertThat(generated).hasSize(2).allSatisfy {
                assertThat(it).isInstanceOf(Decision.Skip::class.java); it as Decision.Skip
                assertThat(it.reasoning.mainReason).isEqualTo(TestSkipReason.noExamplesNon2xxAndNon400())
                assertThat(it.reasoning.otherReasons).isEmpty()
            }
        }
    }

    @Nested
    inner class NegativeGenerationTests {
        @Test
        fun `negative generation should only execute scenarios with explicit negative reasoning`() {
            val feature = featureFromResourceOpenapi("feature_decision_matrix.yaml")
            val generated = feature.negativeTestScenariosWithDecision(
                scenarios = feature.scenarios.asSequence().map { Decision.execute(it) },
                originalScenarios = feature.scenarios
            ).toList()

            assertThat(generated).hasSize(8).allSatisfy { decision ->
                assertThat(decision).isInstanceOf(Decision.Execute::class.java)
                assertThat(decision.context.isNegative).isTrue()
                assertThat(decision.reasoning.mainReason).isEqualTo(TestExecutionReason.NEGATIVE_GENERATION_ENABLED)
                assertThat(decision.reasoning.otherReasons).isEmpty()
            }
        }

        @Test
        fun `negative generation should skip scenarios without examples in strict mode`() {
            val feature = featureFromResourceOpenapi("has_400_example_for_stub.yaml").copy(strictMode = true)
            val scenario = feature.scenarios.first { it.httpResponsePattern.status == 200 }
            val generated = feature.negativeTestScenariosWithDecision(
                scenarios = sequenceOf(Decision.execute(scenario)),
                originalScenarios = listOf(scenario)
            ).toList()

            assertThat(generated).hasSize(1)
            val decision = generated.filterIsInstance<Decision.Skip<Scenario>>().single()
            assertThat(decision.reasoning.mainReason).isEqualTo(TestSkipReason.noExamples2xxAnd400(true))
            assertThat(decision.reasoning.otherReasons).isEmpty()
        }

        @Test
        fun `negative generation should stop when bad request or default scenarios are filtered out of the original scenario list`() {
            val fullFeature = featureFromResourceOpenapi("feature_decision_matrix.yaml")
            val successScenario = fullFeature.scenarios.first { it.httpResponsePattern.status == 200 }
            val badRequestScenario = fullFeature.scenarios.first { it.matchesOperationId(successScenario) && it.status == 400 }

            val feature = fullFeature.copy(scenarios = listOf(successScenario))
            val generatedWithBadRequestFilteredOut = feature.negativeTestScenariosWithDecision(
                scenarios = sequenceOf(Decision.execute(successScenario)),
                originalScenarios = listOf(successScenario, badRequestScenario)
            ).toList()

            assertThat(generatedWithBadRequestFilteredOut).isEmpty()
        }

        @Test
        fun `negative generation should still execute when 2xx is filtered but matching 400 is available`() {
            val fullFeature = featureFromResourceOpenapi("feature_decision_matrix.yaml")
            val successScenario = fullFeature.scenarios.first { it.httpResponsePattern.status == 200 }
            val badRequestScenario = fullFeature.scenarios.first { it.matchesOperationId(successScenario) && it.status == 400 }

            val feature = fullFeature.copy(scenarios = listOf(badRequestScenario))
            val generated = feature.negativeTestScenariosWithDecision(
                originalScenarios = listOf(successScenario, badRequestScenario),
                scenarios = sequenceOf(
                    Decision.Skip(context = successScenario, reasoning = Reasoning(mainReason = TestSkipReason.noExamples2xxAnd400(true))),
                    Decision.execute(badRequestScenario)
                ),
            ).toList()

            assertThat(generated).isNotEmpty()
            assertThat(generated).allSatisfy { decision ->
                assertThat(decision).isInstanceOf(Decision.Execute::class.java)
                assertThat(decision.context.isNegative).isTrue()
                assertThat(decision.reasoning.mainReason).isEqualTo(TestExecutionReason.NEGATIVE_GENERATION_ENABLED)
            }
        }

        @Test
        fun `negative generation should not execute when matching 400 is filtered out`() {
            val fullFeature = featureFromResourceOpenapi("feature_decision_matrix.yaml")
            val successScenario = fullFeature.scenarios.first { it.httpResponsePattern.status == 200 }
            val badRequestScenario = fullFeature.scenarios.first { it.matchesOperationId(successScenario) && it.status == 400 }

            val feature = fullFeature.copy(scenarios = listOf(successScenario))
            val generated = feature.negativeTestScenariosWithDecision(
                originalScenarios = listOf(successScenario, badRequestScenario),
                scenarios = sequenceOf(
                    Decision.Skip(
                        context = successScenario,
                        reasoning = Reasoning(mainReason = TestSkipReason.noExamples2xxAnd400(true))
                    ),
                    Decision.Skip(
                        context = badRequestScenario,
                        reasoning = Reasoning(mainReason = TestSkipReason.noExamples2xxAnd400(true))
                    )
                ),
            ).toList()

            assertThat(generated).isEmpty()
        }
    }

    @Nested
    inner class AcceptCompatibilityTests {
        @Test
        fun `compatible accept should stay executable with original reasoning`() {
            val scenario = Scenario(
                name = "compatible accept",
                specType = SpecType.OPENAPI,
                protocol = SpecmaticProtocol.HTTP,
                httpRequestPattern = HttpRequestPattern(
                    method = "GET",
                    httpPathPattern = buildHttpPathPattern("/products"),
                    headersPattern = HttpHeadersPattern(mapOf(ACCEPT to ExactValuePattern(StringValue("application/json"))))
                ),
                httpResponsePattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(contentType = "application/json")),
            )

            val feature = Feature(scenarios = listOf(scenario), name = "accept-compatible", protocol = SpecmaticProtocol.HTTP)
            val generated = feature.generateContractTestsWithDecision(
                originalScenarios = listOf(scenario),
                scenarios = sequenceOf(Decision.execute(scenario))
            ).toList()

            assertThat(generated).hasSize(1)
            val decision = generated.single()
            assertThat(decision).isInstanceOf(Decision.Execute::class.java)
            assertThat(decision.reasoning.mainReason).isEqualTo(TestExecutionReason.NO_EXAMPLE)
            assertThat(decision.reasoning.otherReasons).isEmpty()
        }

        @Test
        fun `incompatible accept enum with examples should produce one execute and one accept mismatch skip`() {
            val scenario = Scenario(
                specType = SpecType.OPENAPI,
                protocol = SpecmaticProtocol.HTTP,
                name = "example-backed accept mismatch",
                httpRequestPattern = HttpRequestPattern(
                    method = "GET",
                    httpPathPattern = buildHttpPathPattern("/products"),
                    headersPattern = HttpHeadersPattern(mapOf(ACCEPT to EnumPattern(values = listOf(StringValue("application/json"), StringValue("application/xml")))))
                ),
                httpResponsePattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(contentType = "application/json")),
                examples = listOf(Examples(columnNames = listOf("id"), rows = listOf(Row(columnNames = listOf("id"), values = listOf("123"))))),
            )

            val feature = Feature(scenarios = listOf(scenario), name = "accept-mismatch", protocol = SpecmaticProtocol.HTTP)
            val generated = feature.generateContractTestsWithDecision(
                originalScenarios = listOf(scenario),
                scenarios = sequenceOf(Decision.execute(scenario))
            ).toList()

            assertThat(generated).hasSize(2)
            val execute = generated.filterIsInstance<Decision.Execute<ContractTest, Scenario>>().single()
            val executeTestScenario = (execute.value as ScenarioAsTest).scenario
            assertThat(executeTestScenario.httpRequestPattern.headersPattern.pattern.getValue(ACCEPT)).isEqualTo(ExactValuePattern(StringValue("application/json")))
            assertThat(execute.reasoning.mainReason).isEqualTo(TestExecutionReason.HAS_EXAMPLE)
            assertThat(execute.reasoning.otherReasons).isEmpty()

            val skip = generated.filterIsInstance<Decision.Skip<Scenario>>().single()
            assertThat(skip.context.httpRequestPattern.headersPattern.pattern.getValue(ACCEPT)).isEqualTo(ExactValuePattern(StringValue("application/xml")))
            assertThat(skip.reasoning).isEqualTo(Reasoning(mainReason = TestSkipReason.ACCEPT_MISMATCH))
        }

        @Test
        fun `incompatible exact accept without examples should be skipped without any trace`() {
            val scenario = Scenario(
                specType = SpecType.OPENAPI,
                protocol = SpecmaticProtocol.HTTP,
                name = "no-example accept mismatch",
                httpRequestPattern = HttpRequestPattern(
                    method = "GET",
                    httpPathPattern = buildHttpPathPattern("/products"),
                    headersPattern = HttpHeadersPattern(mapOf(ACCEPT to ExactValuePattern(StringValue("application/xml"))))
                ),
                httpResponsePattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(contentType = "application/json")),
            )

            val feature = Feature(scenarios = listOf(scenario), name = "accept-mismatch-no-example", protocol = SpecmaticProtocol.HTTP)
            val generated = feature.generateContractTestsWithDecision(
                originalScenarios = listOf(scenario),
                scenarios = sequenceOf(Decision.execute(scenario))
            ).toList()

            assertThat(generated).isEmpty()
        }

        @Test
        fun `string accept should normalize to response content type during execution`() {
            val scenario = Scenario(
                specType = SpecType.OPENAPI,
                protocol = SpecmaticProtocol.HTTP,
                name = "normalize accept to content type",
                httpRequestPattern = HttpRequestPattern(
                    method = "GET",
                    httpPathPattern = buildHttpPathPattern("/products"),
                    headersPattern = HttpHeadersPattern(mapOf(ACCEPT to StringPattern()))
                ),
                httpResponsePattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(contentType = "application/json")),
            )

            val feature = Feature(scenarios = listOf(scenario), name = "accept-normalization", protocol = SpecmaticProtocol.HTTP)
            val generated = feature.generateContractTestsWithDecision(
                originalScenarios = listOf(scenario),
                scenarios = sequenceOf(Decision.execute(scenario))
            ).toList()

            assertThat(generated).hasSize(1)
            val decision = generated.single() as Decision.Execute
            assertThat(decision.reasoning.mainReason).isEqualTo(TestExecutionReason.NO_EXAMPLE)
            assertThat(decision.reasoning.otherReasons).isEmpty()

            val result = decision.value.runTest(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.headers.getCaseInsensitive(ACCEPT)?.value).isEqualTo("application/json")
                    return HttpResponse(200, body = EmptyString, headers = mapOf(CONTENT_TYPE to "application/json"))
                }
            })
            assertThat(result.result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `string accept should normalize to wildcard when response content type is unknown`() {
            val scenario = Scenario(
                specType = SpecType.OPENAPI,
                protocol = SpecmaticProtocol.HTTP,
                name = "wildcard accept normalization",
                httpRequestPattern = HttpRequestPattern(
                    method = "GET",
                    httpPathPattern = buildHttpPathPattern("/products"),
                    headersPattern = HttpHeadersPattern(mapOf(ACCEPT to StringPattern()))
                ),
                httpResponsePattern = HttpResponsePattern(status = 200),
            )

            val feature = Feature(scenarios = listOf(scenario), name = "accept-wildcard", protocol = SpecmaticProtocol.HTTP)
            val generated = feature.generateContractTestsWithDecision(
                originalScenarios = listOf(scenario),
                scenarios = sequenceOf(Decision.execute(scenario))
            ).toList()

            assertThat(generated).hasSize(1)
            val decision = generated.single() as Decision.Execute
            assertThat(decision.reasoning.mainReason).isEqualTo(TestExecutionReason.NO_EXAMPLE)
            assertThat(decision.reasoning.otherReasons).isEmpty()

            val sentAcceptValues = mutableListOf<String?>()
            val result = decision.value.runTest(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    sentAcceptValues.add(request.headers.getCaseInsensitive(ACCEPT)?.value)
                    return HttpResponse(200)
                }
            })

            assertThat(result.result).isInstanceOf(Result.Success::class.java)
            assertThat(sentAcceptValues).contains("*/*")
        }
    }
}
