package io.specmatic.core

import io.specmatic.core.pattern.ContractException
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class NestedObjectQueryParamSyntaxTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("parseCases")
    fun `schema-guided parser should parse supported object query syntaxes`(
        @Suppress("UNUSED_PARAMETER") name: String,
        key: String,
        syntax: ObjectQuerySyntax,
        expectedPath: QueryObjectPath
    ) {
        val parsedPath = ObjectQueryKeyParser.parse(
            key = key,
            parameterName = "details",
            schema = personDetailsSchema,
            syntax = syntax
        )

        assertThat(parsedPath).isEqualTo(expectedPath)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serializeCases")
    fun `serializer should write semantic paths using the inferred syntax`(
        @Suppress("UNUSED_PARAMETER") name: String,
        syntax: ObjectQuerySyntax,
        expectedKey: String
    ) {
        val key = ObjectQueryKeySerializer.serialize(
            path = addressStreetPath,
            parameterName = "details",
            syntax = syntax
        )

        assertThat(key).isEqualTo(expectedKey)
    }

    @Test
    fun `dot token named zero should be treated as an object property when schema position is object`() {
        val parsedPath = ObjectQueryKeyParser.parse(
            key = "address.0.street",
            parameterName = "details",
            schema = addressWithZeroPropertySchema,
            syntax = ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )

        assertThat(parsedPath).isEqualTo(
            QueryObjectPath(
                listOf(
                    QueryObjectPathToken.Property("address"),
                    QueryObjectPathToken.Property("0"),
                    QueryObjectPathToken.Property("street")
                )
            )
        )
    }

    @Test
    fun `bracket token named zero should be treated as an object property when schema position is object`() {
        val parsedPath = ObjectQueryKeyParser.parse(
            key = "address[0][street]",
            parameterName = "details",
            schema = addressWithZeroPropertySchema,
            syntax = ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
        )

        assertThat(parsedPath).isEqualTo(
            QueryObjectPath(
                listOf(
                    QueryObjectPathToken.Property("address"),
                    QueryObjectPathToken.Property("0"),
                    QueryObjectPathToken.Property("street")
                )
            )
        )
    }

    @Test
    fun `array schema position should reject non-index tokens`() {
        assertThatThrownBy {
            ObjectQueryKeyParser.parse(
                key = "address.street",
                parameterName = "details",
                schema = personDetailsSchema,
                syntax = ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
            )
        }.isInstanceOf(ContractException::class.java)
            .hasMessageContaining("Expected an array index")
    }

    @Test
    fun `object schema position should reject unknown properties without additionalProperties`() {
        assertThatThrownBy {
            ObjectQueryKeyParser.parse(
                key = "address[0].postcode",
                parameterName = "details",
                schema = personDetailsSchema,
                syntax = ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
            )
        }.isInstanceOf(ContractException::class.java)
            .hasMessageContaining("Unknown query object property \"postcode\"")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedKeyCases")
    fun `parser should reject malformed nested query keys`(
        @Suppress("UNUSED_PARAMETER") name: String,
        key: String,
        expectedMessage: String
    ) {
        assertThatThrownBy {
            ObjectQueryKeyParser.parse(
                key = key,
                parameterName = "details",
                schema = personDetailsSchema,
                syntax = ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
            )
        }.isInstanceOf(ContractException::class.java)
            .hasMessageContaining(expectedMessage)
    }

    @Test
    fun `parser should reject nested keys below an ambiguous object schema`() {
        assertThatThrownBy {
            ObjectQueryKeyParser.parse(
                key = "address.street",
                parameterName = "details",
                schema = ambiguousAddressSchema,
                syntax = ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
            )
        }.isInstanceOf(ContractException::class.java)
            .hasMessageContaining("Ambiguous query object schema at address")
    }

    @Test
    fun `syntax inference should not require examples for object query params with only top-level scalars`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = scalarOnlyDetailsSchema,
            examples = emptyList()
        )

        assertThat(result).isEqualTo(NestedQuerySyntaxInferenceResult.SyntaxNotRequired)
    }

    @Test
    fun `syntax inference should fail when nested object query params have no examples`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = personDetailsSchema,
            examples = emptyList()
        )

        assertThat(result).isInstanceOf(NestedQuerySyntaxInferenceResult.Failure::class.java)
        assertThat((result as NestedQuerySyntaxInferenceResult.Failure).messages)
            .contains("No example of query parameter details demonstrates how nested properties should be serialized as query parameters.")
    }

    @Test
    fun `syntax inference should assume dot notation when examples do not cover a nested branch`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = personDetailsSchema,
            examples = listOf("name=Jack")
        )

        assertThat(result).isEqualTo(
            NestedQuerySyntaxInferenceResult.SyntaxInferred(
                ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
            )
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("syntaxInferenceCases")
    fun `syntax inference should infer supported nested object query syntaxes from examples`(
        @Suppress("UNUSED_PARAMETER") name: String,
        example: String,
        expectedSyntax: ObjectQuerySyntax
    ) {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = personDetailsSchema,
            examples = listOf(example)
        )

        assertThat(result).isEqualTo(NestedQuerySyntaxInferenceResult.SyntaxInferred(expectedSyntax))
    }

    @Test
    fun `syntax inference should URL-decode example keys before inferring nested syntax`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = personDetailsSchema,
            examples = listOf("name=Jack&address%5B0%5D.street=Baker%20Street")
        )

        assertThat(result).isEqualTo(
            NestedQuerySyntaxInferenceResult.SyntaxInferred(
                ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
            )
        )
    }

    @Test
    fun `syntax inference should assume dot notation when an array object example does not reach a scalar leaf`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = personDetailsSchema,
            examples = listOf("name=Jack&address[0]=Baker Street")
        )

        assertThat(result).isEqualTo(
            NestedQuerySyntaxInferenceResult.SyntaxInferred(
                ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
            )
        )
    }

    @Test
    fun `syntax inference should assume dot notation when examples omit a deeper nested object branch`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = nestedLocationSchema,
            examples = listOf("address.street=Baker Street")
        )

        assertThat(result).isEqualTo(
            NestedQuerySyntaxInferenceResult.SyntaxInferred(
                ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
            )
        )
    }

    @Test
    fun `syntax inference should not log a warning for each missing nested branch`() {
        val (stdout, result) = captureStandardOutput {
            NestedObjectQuerySyntaxInference.infer(
                parameterName = "details",
                schema = nestedLocationSchema,
                examples = listOf("address.street=Baker Street")
            )
        }

        assertThat(result).isEqualTo(
            NestedQuerySyntaxInferenceResult.SyntaxInferred(
                ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
            )
        )
        assertThat(stdout).doesNotContain("contains nested branch")
        assertThat(stdout).doesNotContain("Assuming dot property notation")
    }

    @Test
    fun `syntax inference should pick the first complete property syntax when examples imply conflicting property styles`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = personDetailsSchema,
            examples = listOf(
                "name=Jack&address[0].street=Baker Street",
                "name=Jill&address[0][street]=Baker Street"
            )
        )

        assertThat(result).isEqualTo(
            NestedQuerySyntaxInferenceResult.SyntaxInferred(
                ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
            )
        )
    }

    @Test
    fun `syntax inference should pick the first complete root syntax when examples imply conflicting root styles`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = personDetailsSchema,
            examples = listOf(
                "name=Jack&address[0].street=Baker Street",
                "details[name]=Jill&details[address][0].street=Baker Street"
            )
        )

        assertThat(result).isEqualTo(
            NestedQuerySyntaxInferenceResult.SyntaxInferred(
                ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
            )
        )
    }

    @Test
    fun `syntax inference should use example when examples are absent`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = personDetailsSchema,
            parameterExamples = NestedQueryParameterExamples(
                example = "name=Jack&address[0][street]=Baker Street"
            )
        )

        assertThat(result).isEqualTo(
            NestedQuerySyntaxInferenceResult.SyntaxInferred(
                ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
            )
        )
    }

    @Test
    fun `syntax inference should scan examples until an entry demonstrates nested notation`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = personDetailsSchema,
            parameterExamples = NestedQueryParameterExamples(
                example = "name=Jack&address[0][street]=Baker Street",
                examples = listOf(
                    "name=Jill",
                    "name=Jane&address[0][street]=Baker Street",
                    "name=Jude&address[0].street=Baker Street"
                )
            )
        )

        assertThat(result).isEqualTo(
            NestedQuerySyntaxInferenceResult.SyntaxInferred(
                ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
            )
        )
    }

    @Test
    fun `syntax inference should collect root and property guidance across example and examples`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = personDetailsSchema,
            parameterExamples = NestedQueryParameterExamples(
                example = "details[name]=Jack",
                examples = listOf(
                    "address[0][street]=Baker Street",
                    "address[0].street=Downing Street"
                )
            )
        )

        assertThat(result).isEqualTo(
            NestedQuerySyntaxInferenceResult.SyntaxInferred(
                ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
            )
        )
    }

    @Test
    fun `syntax inference should use example when no examples entry demonstrates nested notation`() {
        val result = NestedObjectQuerySyntaxInference.infer(
            parameterName = "details",
            schema = personDetailsSchema,
            parameterExamples = NestedQueryParameterExamples(
                example = "name=Jack&address[0][street]=Baker Street",
                examples = listOf("name=Jill")
            )
        )

        assertThat(result).isEqualTo(
            NestedQuerySyntaxInferenceResult.SyntaxInferred(
                ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
            )
        )
    }

    companion object {
        private val addressStreetPath = QueryObjectPath(
            listOf(
                QueryObjectPathToken.Property("address"),
                QueryObjectPathToken.Index(0),
                QueryObjectPathToken.Property("street")
            )
        )

        private val personDetailsSchema = NestedQuerySchema.Object(
            properties = mapOf(
                "name" to NestedQuerySchema.Scalar,
                "address" to NestedQuerySchema.Array(
                    itemSchema = NestedQuerySchema.Object(
                        properties = mapOf(
                            "street" to NestedQuerySchema.Scalar,
                            "city" to NestedQuerySchema.Scalar
                        )
                    )
                )
            )
        )

        private val scalarOnlyDetailsSchema = NestedQuerySchema.Object(
            properties = mapOf("name" to NestedQuerySchema.Scalar)
        )

        private val nestedLocationSchema = NestedQuerySchema.Object(
            properties = mapOf(
                "address" to NestedQuerySchema.Object(
                    properties = mapOf(
                        "street" to NestedQuerySchema.Scalar,
                        "location" to NestedQuerySchema.Object(
                            properties = mapOf("city" to NestedQuerySchema.Scalar)
                        )
                    )
                )
            )
        )

        private val addressWithZeroPropertySchema = NestedQuerySchema.Object(
            properties = mapOf(
                "address" to NestedQuerySchema.Object(
                    properties = mapOf(
                        "0" to NestedQuerySchema.Object(
                            properties = mapOf("street" to NestedQuerySchema.Scalar)
                        )
                    )
                )
            )
        )

        private val ambiguousAddressSchema = NestedQuerySchema.Object(
            properties = mapOf(
                "address" to NestedQuerySchema.Ambiguous("oneOf object query shapes are not supported")
            )
        )

        @JvmStatic
        fun parseCases(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    "unwrapped bracket property syntax",
                    "address[0][street]",
                    ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket),
                    addressStreetPath
                ),
                Arguments.of(
                    "unwrapped dot property syntax",
                    "address[0].street",
                    ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket),
                    addressStreetPath
                ),
                Arguments.of(
                    "parameter wrapped dot property syntax",
                    "details[address][0].street",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket),
                    addressStreetPath
                ),
                Arguments.of(
                    "parameter wrapped bracket property syntax",
                    "details[address][0][street]",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket),
                    addressStreetPath
                ),
                Arguments.of(
                    "parameter dot-wrapped dot property syntax",
                    "details.address[0].street",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameDotWrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket),
                    addressStreetPath
                ),
                Arguments.of(
                    "parameter dot-wrapped bracket property syntax",
                    "details.address[0][street]",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameDotWrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket),
                    addressStreetPath
                )
            )
        }

        @JvmStatic
        fun malformedKeyCases(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("unclosed bracket", "address[0][street", "Unclosed bracket"),
                Arguments.of("empty bracket", "address[][street]", "Empty bracket token"),
                Arguments.of("empty dot", "address[0].", "Empty dot token"),
                Arguments.of("invalid segment after bracket", "address[0]street", "Could not parse query object key segment street")
            )
        }

        @JvmStatic
        fun syntaxInferenceCases(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    "unwrapped bracket properties and bracket indexes",
                    "name=Jack&address[0][street]=Baker Street",
                    ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
                ),
                Arguments.of(
                    "unwrapped dot properties and bracket indexes",
                    "name=Jack&address[0].street=Baker Street",
                    ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
                ),
                Arguments.of(
                    "parameter-wrapped root with dot properties and bracket indexes",
                    "details[name]=Jack&details[address][0].street=Baker Street",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
                ),
                Arguments.of(
                    "parameter-wrapped root with bracket properties and bracket indexes",
                    "details[name]=Jack&details[address][0][street]=Baker Street",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
                ),
                Arguments.of(
                    "parameter dot-wrapped root with dot properties and bracket indexes",
                    "details.name=Jack&details.address[0].street=Baker Street",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameDotWrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
                ),
                Arguments.of(
                    "parameter dot-wrapped root with bracket properties and bracket indexes",
                    "details.name=Jack&details.address[0][street]=Baker Street",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameDotWrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
                )
            )
        }

        @JvmStatic
        fun serializeCases(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    "unwrapped bracket property syntax",
                    ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket),
                    "address[0][street]"
                ),
                Arguments.of(
                    "unwrapped dot property syntax",
                    ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket),
                    "address[0].street"
                ),
                Arguments.of(
                    "parameter wrapped dot property syntax",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket),
                    "details[address][0].street"
                ),
                Arguments.of(
                    "parameter wrapped bracket property syntax",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameWrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket),
                    "details[address][0][street]"
                ),
                Arguments.of(
                    "parameter dot-wrapped dot property syntax",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameDotWrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket),
                    "details.address[0].street"
                ),
                Arguments.of(
                    "parameter dot-wrapped bracket property syntax",
                    ObjectQuerySyntax(ObjectQueryRoot.ParameterNameDotWrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket),
                    "details.address[0][street]"
                )
            )
        }
    }
}
