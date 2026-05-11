package application.backwardCompatibility

import io.specmatic.core.backwardCompatibility.OperationChangeKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BackwardCompatibilityChangeInfoTest {
    @TempDir
    private lateinit var tempDir: File

    @TempDir
    private lateinit var remoteDir: File

    @BeforeEach
    fun setup() {
        run("git", "init", "--bare", "--initial-branch=master", dir = remoteDir)
        run("git", "init", "--initial-branch=master", dir = tempDir)
        run("git", "config", "--local", "user.name", "developer", dir = tempDir)
        run("git", "config", "--local", "user.email", "developer@example.com", dir = tempDir)
        run("git", "remote", "add", "origin", remoteDir.absolutePath, dir = tempDir)
    }

    @AfterEach
    fun cleanup() {
        tempDir.deleteRecursively()
        remoteDir.deleteRecursively()
    }

    @Test
    fun `populates change info per operation kind for an edited OpenAPI spec`() {
        val oldSpec = """
            openapi: 3.0.0
            info:
              title: pets
              version: "1"
            paths:
              /pets:
                get:
                  summary: list pets
                  responses:
                    "200":
                      description: ok
              /pets/{id}:
                get:
                  summary: get a pet
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: string
                  responses:
                    "200":
                      description: ok
        """.trimIndent()

        val specFile = File(tempDir, "pets.yaml").apply { writeText(oldSpec) }
        commitAndPush("Initial commit")

        val newSpec = """
            openapi: 3.0.0
            info:
              title: pets
              version: "1"
            paths:
              /pets:
                get:
                  summary: list pets
                  responses:
                    "200":
                      description: ok
                post:
                  summary: create a pet
                  responses:
                    "201":
                      description: created
              /pets/{id}:
                get:
                  summary: get a pet by identifier
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: string
                    - name: verbose
                      in: query
                      required: false
                      schema:
                        type: boolean
                  responses:
                    "200":
                      description: ok
        """.trimIndent()

        specFile.writeText(newSpec)

        val command = BackwardCompatibilityCheckCommandV2().apply {
            options.repoDir = tempDir.canonicalPath
        }
        command.call()

        val changeInfo = command.lastRunChangeInfo.entries.firstOrNull { it.key.endsWith("pets.yaml") }?.value
        assertThat(changeInfo).isNotNull
        val byId = changeInfo!!.logical.associateBy { it.identifier }

        val getPets = byId["GET /pets"]
        assertThat(getPets).isNotNull
        assertThat(getPets!!.kind).isEqualTo(OperationChangeKind.UNCHANGED)
        assertThat(getPets.anchor).isNotNull
        assertThat(getPets.overlapsHunk).isFalse()
        assertThat(getPets.semanticallyDiffers).isFalse()

        val postPets = byId["POST /pets"]
        assertThat(postPets).isNotNull
        assertThat(postPets!!.kind).isEqualTo(OperationChangeKind.ADDED)
        assertThat(postPets.anchor).isNotNull

        val getById = byId["GET /pets/{id}"]
        assertThat(getById).isNotNull
        assertThat(getById!!.kind).isEqualTo(OperationChangeKind.MODIFIED)
        assertThat(getById.anchor).isNotNull
        assertThat(getById.overlapsHunk || getById.semanticallyDiffers).isTrue()

        assertThat(changeInfo.physical).isNotNull
        assertThat(changeInfo.physical!!.newHunks).isNotEmpty
    }

    @Test
    fun `marks a brand new file with all operations as ADDED and no physical hunks`() {
        File(tempDir, "base.yaml").writeText(MINIMAL_SPEC)
        commitAndPush("Initial commit")
        run("git", "checkout", "-b", "feature", dir = tempDir)

        File(tempDir, "new-api.yaml").writeText(
            """
            openapi: 3.0.0
            info:
              title: orders
              version: "1"
            paths:
              /orders:
                get:
                  responses:
                    "200":
                      description: ok
            """.trimIndent()
        )
        run("git", "add", "new-api.yaml", dir = tempDir)
        run("git", "commit", "-m", "Add new spec", dir = tempDir)

        val command = BackwardCompatibilityCheckCommandV2().apply {
            options.repoDir = tempDir.canonicalPath
            options.baseBranch = "master"
        }
        command.call()

        val changeInfo = command.lastRunChangeInfo.entries.firstOrNull { it.key.endsWith("new-api.yaml") }?.value
        assertThat(changeInfo).isNotNull
        assertThat(changeInfo!!.physical).isNull()
        val getOrders = changeInfo.logical.firstOrNull { it.identifier == "GET /orders" }
        assertThat(getOrders).isNotNull
        assertThat(getOrders!!.kind).isEqualTo(OperationChangeKind.ADDED)
    }

    @Test
    fun `component schema changes mark only operations that reference the changed component`() {
        val specFile = File(tempDir, "api.yaml").apply { writeText(specWithComponentSchemas(petIdType = "string")) }
        commitAndPush("Initial commit")

        specFile.writeText(specWithComponentSchemas(petIdType = "integer"))

        val byId = changesById(runCommand(), "api.yaml")

        assertThat(byId["GET /pets"]!!.kind).isEqualTo(OperationChangeKind.MODIFIED)
        assertThat(byId["GET /pets"]!!.semanticallyDiffers).isTrue()
        assertThat(byId["GET /orders"]!!.kind).isEqualTo(OperationChangeKind.UNCHANGED)
    }

    @Test
    fun `external schema ref changes mark the operation that resolves the ref`() {
        val schemasDir = File(tempDir, "schemas").apply { mkdirs() }
        val petSchema = File(schemasDir, "pet.yaml").apply { writeText(petSchema(type = "string")) }
        File(tempDir, "api.yaml").writeText(specWithExternalSchemaRef())
        commitAndPush("Initial commit")

        petSchema.writeText(petSchema(type = "integer"))

        val byId = changesById(runCommand(), "api.yaml")

        assertThat(byId["GET /pets"]!!.kind).isEqualTo(OperationChangeKind.MODIFIED)
        assertThat(byId["GET /pets"]!!.semanticallyDiffers).isTrue()
        assertThat(byId["GET /orders"]!!.kind).isEqualTo(OperationChangeKind.UNCHANGED)
    }

    @Test
    fun `external component schema ref changes mark operations that transitively use it`() {
        val schemasDir = File(tempDir, "schemas").apply { mkdirs() }
        val petSchema = File(schemasDir, "pet.yaml").apply { writeText(petSchema(type = "string")) }
        File(tempDir, "api.yaml").writeText(specWithExternalComponentSchemaRef())
        commitAndPush("Initial commit")

        petSchema.writeText(petSchema(type = "integer"))

        val byId = changesById(runCommand(), "api.yaml")

        assertThat(byId["GET /pets"]!!.kind).isEqualTo(OperationChangeKind.MODIFIED)
        assertThat(byId["GET /pets"]!!.semanticallyDiffers).isTrue()
        assertThat(byId["GET /orders"]!!.kind).isEqualTo(OperationChangeKind.UNCHANGED)
    }

    @Test
    fun `nested component schema refs mark only transitively referencing operations`() {
        val specFile = File(tempDir, "api.yaml").apply { writeText(specWithNestedComponentSchemas(petIdType = "string")) }
        commitAndPush("Initial commit")

        specFile.writeText(specWithNestedComponentSchemas(petIdType = "integer"))

        val byId = changesById(runCommand(), "api.yaml")

        assertThat(byId["GET /pets"]!!.kind).isEqualTo(OperationChangeKind.MODIFIED)
        assertThat(byId["GET /pets"]!!.semanticallyDiffers).isTrue()
        assertThat(byId["GET /orders"]!!.kind).isEqualTo(OperationChangeKind.UNCHANGED)
    }

    @Test
    fun `path item refs get operation anchors and detect resolved changes`() {
        val specFile = File(tempDir, "api.yaml").apply { writeText(specWithPathItemRef(petIdType = "string")) }
        commitAndPush("Initial commit")

        specFile.writeText(specWithPathItemRef(petIdType = "integer"))

        val byId = changesById(runCommand(), "api.yaml")

        val getPets = byId["GET /pets"]
        assertThat(getPets).isNotNull
        assertThat(getPets!!.kind).isEqualTo(OperationChangeKind.MODIFIED)
        assertThat(getPets.anchor).isNotNull
        assertThat(getPets.overlapsHunk || getPets.semanticallyDiffers).isTrue()
    }

    @Test
    fun `removed operations are reported as REMOVED`() {
        val specFile = File(tempDir, "api.yaml").apply { writeText(specWithTwoOperations(includePets = true)) }
        commitAndPush("Initial commit")

        specFile.writeText(specWithTwoOperations(includePets = false))

        val byId = changesById(runCommand(), "api.yaml")

        assertThat(byId["GET /pets"]!!.kind).isEqualTo(OperationChangeKind.REMOVED)
        assertThat(byId["GET /orders"]!!.kind).isEqualTo(OperationChangeKind.UNCHANGED)
    }

    @Test
    fun `json specs produce anchors and logical changes`() {
        val specFile = File(tempDir, "api.json").apply { writeText(jsonSpec(includePost = false)) }
        commitAndPush("Initial commit")

        specFile.writeText(jsonSpec(includePost = true))

        val byId = changesById(runCommand(), "api.json")

        val postPets = byId["POST /pets"]
        assertThat(postPets).isNotNull
        assertThat(postPets!!.kind).isEqualTo(OperationChangeKind.ADDED)
        assertThat(postPets.anchor).isNotNull
    }

    @Test
    fun `mixed committed and uncommitted edits use current file line numbers for hunks`() {
        val specFile = File(tempDir, "api.yaml").apply { writeText(specForMixedDiff(betaSummary = "old beta", includeInserted = false)) }
        commitAndPush("Initial commit")
        run("git", "checkout", "-b", "feature", dir = tempDir)

        specFile.writeText(specForMixedDiff(betaSummary = "committed beta", includeInserted = false))
        run("git", "add", "api.yaml", dir = tempDir)
        run("git", "commit", "-m", "Change beta", dir = tempDir)
        specFile.writeText(specForMixedDiff(betaSummary = "committed beta", includeInserted = true))

        val byId = changesById(runCommand(baseBranch = "master"), "api.yaml")

        assertThat(byId["GET /inserted"]!!.kind).isEqualTo(OperationChangeKind.ADDED)
        assertThat(byId["GET /beta"]!!.kind).isEqualTo(OperationChangeKind.MODIFIED)
        assertThat(byId["GET /beta"]!!.overlapsHunk).isTrue()
    }

    private fun commitAndPush(message: String) {
        run("git", "add", ".", dir = tempDir)
        run("git", "commit", "-m", message, dir = tempDir)
        run("git", "push", "origin", "master", dir = tempDir)
    }

    private fun run(vararg args: String, dir: File) {
        val exitCode = ProcessBuilder(*args).directory(dir).inheritIO().start().waitFor()
        assertThat(exitCode).describedAs(args.joinToString(" ")).isEqualTo(0)
    }

    private fun runCommand(baseBranch: String? = null): BackwardCompatibilityCheckCommandV2 {
        return BackwardCompatibilityCheckCommandV2().apply {
            options.repoDir = tempDir.canonicalPath
            options.baseBranch = baseBranch
            call()
        }
    }

    private fun changesById(command: BackwardCompatibilityCheckCommandV2, fileName: String) =
        command.lastRunChangeInfo.entries.first { it.key.endsWith(fileName) }.value.logical.associateBy { it.identifier }

    private fun specWithComponentSchemas(petIdType: String): String = """
        openapi: 3.0.0
        info:
          title: refs
          version: "1"
        paths:
          /pets:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        ${"$"}ref: '#/components/schemas/Pet'
          /orders:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        ${"$"}ref: '#/components/schemas/Order'
        components:
          schemas:
            Pet:
              type: object
              properties:
                id:
                  type: $petIdType
            Order:
              type: object
              properties:
                id:
                  type: string
    """.trimIndent()

    private fun specWithExternalSchemaRef(): String = """
        openapi: 3.0.0
        info:
          title: external refs
          version: "1"
        paths:
          /pets:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        ${"$"}ref: './schemas/pet.yaml'
          /orders:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          id:
                            type: string
    """.trimIndent()

    private fun petSchema(type: String): String = """
        type: object
        properties:
          id:
            type: $type
    """.trimIndent()

    private fun specWithExternalComponentSchemaRef(): String = """
        openapi: 3.0.0
        info:
          title: external component refs
          version: "1"
        paths:
          /pets:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        ${"$"}ref: '#/components/schemas/Pet'
          /orders:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          id:
                            type: string
        components:
          schemas:
            Pet:
              ${"$"}ref: './schemas/pet.yaml'
    """.trimIndent()

    private fun specWithNestedComponentSchemas(petIdType: String): String = """
        openapi: 3.0.0
        info:
          title: nested refs
          version: "1"
        paths:
          /pets:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        ${"$"}ref: '#/components/schemas/Pet'
          /orders:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        ${"$"}ref: '#/components/schemas/Order'
        components:
          schemas:
            Pet:
              type: object
              properties:
                id:
                  ${"$"}ref: '#/components/schemas/PetId'
            PetId:
              type: $petIdType
            Order:
              type: object
              properties:
                id:
                  type: string
    """.trimIndent()

    private fun specWithPathItemRef(petIdType: String): String = """
        openapi: 3.0.0
        info:
          title: path item refs
          version: "1"
        paths:
          /pets:
            ${"$"}ref: '#/components/pathItems/Pets'
        components:
          pathItems:
            Pets:
              get:
                responses:
                  "200":
                    description: ok
                    content:
                      application/json:
                        schema:
                          type: object
                          properties:
                            id:
                              type: $petIdType
    """.trimIndent()

    private fun specWithTwoOperations(includePets: Boolean): String {
        return buildString {
            appendLine("openapi: 3.0.0")
            appendLine("info:")
            appendLine("  title: removed ops")
            appendLine("  version: \"1\"")
            appendLine("paths:")
            if (includePets) {
                appendLine("  /pets:")
                appendLine("    get:")
                appendLine("      responses:")
                appendLine("        \"200\":")
                appendLine("          description: ok")
            }
            appendLine("  /orders:")
            appendLine("    get:")
            appendLine("      responses:")
            appendLine("        \"200\":")
            appendLine("          description: ok")
        }
    }

    private fun jsonSpec(includePost: Boolean): String {
        val post = if (includePost) ""","post":{"responses":{"201":{"description":"created"}}}""" else ""
        return """{"openapi":"3.0.0","info":{"title":"json","version":"1"},"paths":{"/pets":{"get":{"responses":{"200":{"description":"ok"}}}$post}}}"""
    }

    private fun specForMixedDiff(betaSummary: String, includeInserted: Boolean): String {
        return buildString {
            appendLine("openapi: 3.0.0")
            appendLine("info:")
            appendLine("  title: mixed diffs")
            appendLine("  version: \"1\"")
            appendLine("paths:")
            appendLine("  /alpha:")
            appendLine("    get:")
            appendLine("      responses:")
            appendLine("        \"200\":")
            appendLine("          description: ok")
            if (includeInserted) {
                appendLine("  /inserted:")
                appendLine("    get:")
                appendLine("      responses:")
                appendLine("        \"200\":")
                appendLine("          description: ok")
            }
            appendLine("  /beta:")
            appendLine("    get:")
            appendLine("      summary: $betaSummary")
            appendLine("      responses:")
            appendLine("        \"200\":")
            appendLine("          description: ok")
        }
    }

    companion object {
        private val MINIMAL_SPEC = """
            openapi: 3.0.0
            info:
              title: base
              version: "1"
            paths:
              /ping:
                get:
                  responses:
                    "200":
                      description: ok
        """.trimIndent()
    }
}
