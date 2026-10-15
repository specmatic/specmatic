package io.specmatic.core.overlay

import org.assertj.core.api.AssertionsForClassTypes.assertThat
import org.junit.jupiter.api.Test

class OverlayMergerTest {
    @Test
    fun `updating parent node after child nodes shouldn't override the changes`() {
        val yamlContent = """
            parentNode:
                node1: "existing node1 content"
                node2:
                    subnode: "existing node2 sub node content"
        """.trimIndent()

        val overlayContent = """
            overlay: 1.0.0
            info:
              title: Targeted Overlay
              version: 1.0.0
            actions:
              - target: ${'$'}.parentNode.node1
                update: new node1 content
              - target: ${'$'}.parentNode.node2.subnode
                update: new node2 sub node content
              - target: ${'$'}.parentNode
                update:
                  node4: "new node 4 content"
        """.trimIndent()

        val updatedContent = OverlayMerger().merge(
            baseContent = yamlContent,
            overlay = OverlayParser.parse(overlayContent)
        )

        assertThat(updatedContent).contains("""node1: "new node1 content"""")
        assertThat(updatedContent).contains("""subnode: "new node2 sub node content"""")
        assertThat(updatedContent).contains("""node4: "new node 4 content"""")
    }

    @Test
    fun `should merge the update map`() {
        val yamlContent = """
            node1: "existing node1 content"
            node2:
                subnode: "existing additional content"
            node3:
                newField: "existing value"
        """.trimIndent()

        val overlayContent = """
            overlay: 1.0.0
            info:
              title: Targeted Overlay
              version: 1.0.0
            actions:
              - target: ${'$'}.node1
                update: new node1 content
              - target: ${'$'}.node2.subnode
                update: new additional content
              - target: ${'$'}.node3
                update:
                  newField: new value
        """.trimIndent()

        val updatedContent = OverlayMerger().merge(
            baseContent = yamlContent,
            overlay = OverlayParser.parse(overlayContent)
        )

        assertThat(updatedContent).contains("""node1: "new node1 content"""")
        assertThat(updatedContent).contains("""subnode: "new additional content"""")
        assertThat(updatedContent).contains("""newField: "new value"""")
    }

    @Test
    fun `should merge both update and removal maps`() {
        val yamlContent = """
            node1: "existing node1 content"
            node2:
                subnode: "existing additional content"
            node3:
                newField: "existing value"
        """.trimIndent()

        val overlayContent = """
            overlay: 1.0.0
            info:
              title: Targeted Overlay
              version: 1.0.0
            actions:
              - target: ${'$'}.node1
                update: new node1 content
              - target: ${'$'}.node2.subnode
                remove: true
              - target: ${'$'}.node3
                remove: true
        """.trimIndent()

        val updatedContent = OverlayMerger().merge(
            baseContent = yamlContent,
            overlay = OverlayParser.parse(overlayContent)
        )

        assertThat(updatedContent).contains("""node1: "new node1 content"""")
        assertThat(updatedContent).doesNotContain("subnode")
        assertThat(updatedContent).doesNotContain("node3")
    }

    @Test
    fun `should merge the overlay content into existing map in oas`() {
        val yamlContent = """
            openapi: 3.0.3
            paths:
              /person/{id}:
                get:
                  responses:
                    '200':
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                            required:
                              - id
                              - name
        """.trimIndent()
        val overlayContent = """
            overlay: 1.0.0
            actions:
              - target: ${'$'}.paths['/person/{id}'].get.responses['200'].content.application/json.schema.properties
                update:
                  age:
                    type: integer
                    description: Age of the person
        """.trimIndent()

        val updatedContent = OverlayMerger().merge(
            baseContent = yamlContent,
            overlay = OverlayParser.parse(overlayContent)
        )

        assertThat(updatedContent).containsIgnoringWhitespaces("""
            type: "object"
            properties:
              id:
                type: "integer"
              name:
                type: "string"
              age:
                type: "integer"
                description: "Age of the person"
            required:
              - "id"
              - "name"
        """.trimIndent())
    }

    @Test
    fun `should append the overlay content into an existing array in oas`() {
        val yamlContent = """
            openapi: 3.0.3
            paths:
              /person/{id}:
                get:
                  responses:
                    '200':
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                            required:
                              - id
                              - name
                            enum:
                              - ACTIVE
                              - INACTIVE
        """.trimIndent()
        val overlayContent = """
            overlay: 1.0.0
            actions:
              - target: ${'$'}.paths['/person/{id}'].get.responses['200'].content.application/json.schema.enum
                update: PENDING
        """.trimIndent()

        val updatedContent = OverlayMerger().merge(
            baseContent = yamlContent,
            overlay = OverlayParser.parse(overlayContent)
        )

        assertThat(updatedContent).containsIgnoringWhitespaces("""
        enum:
        - "ACTIVE"
        - "INACTIVE"
        - "PENDING"
        """.trimIndent())
    }

    @Test
    fun `should gracefully return the original if the jsonpath does not exist`() {
        val yamlContent = """
            node1: "existing node1 content"
        """.trimIndent()

        val overlayContent = """
            overlay: 1.0.0
            info:
              title: Targeted Overlay
              version: 1.0.0
            actions:
              - target: ${'$'}.node2
                update: new additional content
        """.trimIndent()

        val updatedContent = OverlayMerger().merge(
            baseContent = yamlContent,
            overlay = OverlayParser.parse(overlayContent)
        )

        assertThat(updatedContent).isEqualTo(yamlContent)
    }
}
