package io.specmatic.conversions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExternalSourceProjectionTest {

    @Test
    fun `targetPointerFor is the identity when the parser kept the schema name`() {
        // common.yaml#/components/schemas/ProductBase imported, unchanged, to the same model pointer.
        val projection = ExternalSourceProjection(
            sourceFile = "common.yaml",
            sourceBasePointer = "/components/schemas/ProductBase",
            targetPointer = "/components/schemas/ProductBase",
        )

        assertThat(projection.targetPointerFor("/components/schemas/ProductBase/properties/name"))
            .isEqualTo("/components/schemas/ProductBase/properties/name")
    }

    @Test
    fun `targetPointerFor swaps the model root when the parser renamed the import`() {
        // Two files both define `Payload`; the parser imported one as `Payload_1` to avoid the
        // collision. The file still calls it `Payload`, so the projection has to remap the root.
        val projection = ExternalSourceProjection(
            sourceFile = "commonB.yaml",
            sourceBasePointer = "/components/schemas/Payload",
            targetPointer = "/components/schemas/Payload_1",
        )

        assertThat(projection.targetPointerFor("/components/schemas/Payload/properties/value"))
            .isEqualTo("/components/schemas/Payload_1/properties/value")
    }

    @Test
    fun `targetPointerFor maps the base pointer itself to the target root`() {
        val projection = ExternalSourceProjection(
            sourceFile = "commonB.yaml",
            sourceBasePointer = "/components/schemas/Payload",
            targetPointer = "/components/schemas/Payload_1",
        )

        assertThat(projection.targetPointerFor("/components/schemas/Payload"))
            .isEqualTo("/components/schemas/Payload_1")
    }

    @Test
    fun `targetPointerFor concatenates onto an empty base for a whole-file ref`() {
        // A whole-file $ref (no #fragment) imports the file's root; the base is empty, so the file
        // pointer is appended verbatim onto wherever the file landed in the model.
        val projection = ExternalSourceProjection(
            sourceFile = "ProductBase.yaml",
            sourceBasePointer = "",
            targetPointer = "/components/schemas/ProductBase",
        )

        assertThat(projection.targetPointerFor("/properties/name"))
            .isEqualTo("/components/schemas/ProductBase/properties/name")
    }

    // --- contains: which file pointers this projection is responsible for translating ---

    @Test
    fun `contains is false for a pointer in a different file`() {
        val projection = ExternalSourceProjection(
            sourceFile = "commonA.yaml",
            sourceBasePointer = "/components/schemas/Payload",
            targetPointer = "/components/schemas/Payload",
        )

        assertThat(projection.contains("commonB.yaml", "/components/schemas/Payload")).isFalse()
    }

    @Test
    fun `contains matches the base pointer exactly and its descendants`() {
        val projection = ExternalSourceProjection(
            sourceFile = "common.yaml",
            sourceBasePointer = "/components/schemas/ProductBase",
            targetPointer = "/components/schemas/ProductBase",
        )

        assertThat(projection.contains("common.yaml", "/components/schemas/ProductBase")).isTrue()
        assertThat(projection.contains("common.yaml", "/components/schemas/ProductBase/properties/name")).isTrue()
    }

    @Test
    fun `contains does not match a sibling whose name merely shares a prefix`() {
        // The trailing '/' in the prefix check exists precisely so that base `.../Payload` does NOT
        // swallow a sibling `.../PayloadExtra`. Without it, one schema's locations would leak onto another's.
        val projection = ExternalSourceProjection(
            sourceFile = "common.yaml",
            sourceBasePointer = "/components/schemas/Payload",
            targetPointer = "/components/schemas/Payload",
        )

        assertThat(projection.contains("common.yaml", "/components/schemas/PayloadExtra")).isFalse()
    }

    @Test
    fun `contains does not match an ancestor of the base pointer`() {
        val projection = ExternalSourceProjection(
            sourceFile = "common.yaml",
            sourceBasePointer = "/components/schemas/ProductBase",
            targetPointer = "/components/schemas/ProductBase",
        )

        assertThat(projection.contains("common.yaml", "/components/schemas")).isFalse()
    }

    @Test
    fun `contains covers every pointer in the file when the base is empty (whole-file ref)`() {
        val projection = ExternalSourceProjection(
            sourceFile = "ProductBase.yaml",
            sourceBasePointer = "",
            targetPointer = "/components/schemas/ProductBase",
        )

        assertThat(projection.contains("ProductBase.yaml", "/properties/name")).isTrue()
        assertThat(projection.contains("ProductBase.yaml", "")).isTrue()
        assertThat(projection.contains("other.yaml", "/properties/name")).isFalse()
    }
}
