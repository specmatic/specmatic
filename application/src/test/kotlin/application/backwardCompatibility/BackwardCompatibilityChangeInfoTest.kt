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

    private fun commitAndPush(message: String) {
        run("git", "add", ".", dir = tempDir)
        run("git", "commit", "-m", message, dir = tempDir)
        run("git", "push", "origin", "master", dir = tempDir)
    }

    private fun run(vararg args: String, dir: File) {
        ProcessBuilder(*args).directory(dir).inheritIO().start().waitFor()
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
