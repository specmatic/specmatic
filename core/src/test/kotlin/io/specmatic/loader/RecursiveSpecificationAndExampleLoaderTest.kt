package io.specmatic.loader

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.SpecmaticGlobalSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class RecursiveSpecificationAndExampleLoaderTest {
    @TempDir
    lateinit var tempDir: Path
    private lateinit var rootDir: File
    private lateinit var config: TestConfig
    private lateinit var strategy: TestLoaderStrategy

    @BeforeEach
    fun setup() {
        rootDir = tempDir.toFile()
        config = TestConfig(specExampleTemplate = "<SPEC_FILE_NAME>_examples", sharedExampleTemplates = listOf("<SPEC_EACH_PARENT>_common", "common"))
        strategy = TestLoaderStrategy()
        println("TEST SETUP")
        println("Root Directory: ${rootDir.absolutePath}")
    }

    @Test
    fun `loadForSpecification should load single spec with its examples`() {
        val structure = directoryStructure {
            spec("user.spec")
            dir("user_examples") {
                example("create.example")
                example("update.example")
            }
            dir("common") {
                example("shared.example")
            }
        }

        structure.printStructure()
        val loader = createLoader()

        val specFile = File(rootDir, "user.spec")
        val result = loader.load(specFile)

        assertThat(result).isNotNull; result!!; logResults(listOf(result))
        assertThat(result.specFile.name).isEqualTo("user.spec")
        assertThat(result.examples.specExamples).hasSize(2)
        assertThat(result.examples.sharedExamples).hasSize(1)
        assertThat(result.examples.specExamples.map { it.name }).containsExactlyInAnyOrder("create.example", "update.example")
        assertThat(result.examples.sharedExamples.first().name).isEqualTo("shared.example")
    }

    @Test
    fun `loadForSpecification should not search outside spec parent directory`() {
        val outsideDir = tempDir.parent.resolve("outside").toFile().apply { mkdirs() }
        val outsideCommon = File(outsideDir, "common").apply { mkdirs() }
        File(outsideCommon, "outside.example").writeText("outside")

        val structure = directoryStructure {
            dir("api") {
                spec("user.spec")
            }
            dir("common") {
                example("root_shared.example")
            }
        }

        structure.printStructure()
        println("\nOutside Structure (should NOT be found): /outside/common/outside.example")
        val loader = createLoader()
        val specFile = File(rootDir, "api/user.spec")

        val result = loader.load(specFile)
        assertThat(result).isNotNull; result!!; logResults(listOf(result))
        assertThat(result.examples.sharedExamples).isEmpty()
        assertThat(result.examples.specExamples).isEmpty()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `should find all specifications in flat directory`() {
        val structure = directoryStructure {
            spec("user.spec")
            spec("payment.spec")
            spec("order.spec")
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(3)
        assertThat(results.map { it.specFile.name }).containsExactlyInAnyOrder("user.spec", "payment.spec", "order.spec")
    }

    @Test
    fun `should find specifications in nested directories`() {
        val structure = directoryStructure {
            dir("api") {
                dir("v1") {
                    spec("user.spec")
                }
                dir("v2") {
                    spec("user.spec")
                    spec("payment.spec")
                }
            }
            dir("internal") {
                spec("admin.spec")
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(4)
        assertThat(results.map { it.specFile.name }).containsExactlyInAnyOrder("user.spec", "user.spec", "payment.spec", "admin.spec")
    }

    @Test
    fun `should find spec examples in sibling directory`() {
        val structure = directoryStructure {
            spec("user.spec")
            dir("user_examples") {
                example("create.example")
                example("update.example")
                example("delete.example")
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(1)
        val userSpec = results.first()
        assertThat(userSpec.examples.specExamples).hasSize(3)
        assertThat(userSpec.examples.sharedExamples).isEmpty()
        assertThat(userSpec.specFile.name).isEqualTo("user.spec")
        assertThat(userSpec.examples.specExamples.map { it.name }).containsExactlyInAnyOrder("create.example", "update.example", "delete.example")
    }

    @Test
    fun `should find spec examples only for matching spec file name`() {
        val structure = directoryStructure {
            spec("user.spec")
            spec("payment.spec")
            dir("user_examples") {
                example("user1.example")
                example("user2.example")
            }
            dir("payment_examples") {
                example("payment1.example")
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        val userSpec = results.find { it.specFile.name == "user.spec" }!!
        assertThat(userSpec.examples.specExamples).hasSize(2)
        assertThat(userSpec.examples.specExamples.map { it.name }).containsExactlyInAnyOrder("user1.example", "user2.example")

        val paymentSpec = results.find { it.specFile.name == "payment.spec" }!!
        assertThat(paymentSpec.examples.specExamples).hasSize(1)
        assertThat(paymentSpec.examples.specExamples.map { it.name }).containsExactlyInAnyOrder("payment1.example")
    }

    @Test
    fun `should not find spec examples in non-matching directories`() {
        val structure = directoryStructure {
            spec("user.spec")
            dir("examples") {
                example("example1.example")
            }
            dir("user_tests") {
                example("example2.example")
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(1)
        assertThat(results.first().examples.specExamples).isEmpty()
    }

    @Test
    fun `should find shared examples in common directory at same level`() {
        val structure = directoryStructure {
            spec("user.spec")
            dir("common") {
                example("shared1.example")
                example("shared2.example")
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(1)
        val userSpec = results.first()
        assertThat(userSpec.examples.specExamples).isEmpty()
        assertThat(userSpec.examples.sharedExamples).hasSize(2)
        assertThat(userSpec.examples.sharedExamples.map { it.name }).containsExactlyInAnyOrder("shared1.example", "shared2.example")
    }

    @Test
    fun `should find shared examples in SPEC_EACH_PARENT_common directory`() {
        val structure = directoryStructure {
            dir("api") {
                spec("user.spec")
                dir("api_common") {
                    example("api_shared1.example")
                    example("api_shared2.example")
                }
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(1)
        val userSpec = results.first()
        assertThat(userSpec.examples.sharedExamples).hasSize(2)
        assertThat(userSpec.examples.sharedExamples.map { it.name }).containsExactlyInAnyOrder("api_shared1.example", "api_shared2.example")
    }

    @Test
    fun `should find shared examples in parent common directory`() {
        val structure = directoryStructure {
            dir("api") {
                dir("v1") {
                    spec("user.spec")
                }
                dir("common") {
                    example("api_common.example")
                }
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(1)
        assertThat(results.first().examples.sharedExamples).hasSize(1)
        assertThat(results.first().examples.sharedExamples.first().name).isEqualTo("api_common.example")
    }

    @Test
    fun `should find shared examples at multiple parent levels`() {
        val structure = directoryStructure {
            dir("common") {
                example("root_shared.example")
            }
            dir("api") {
                dir("common") {
                    example("api_shared.example")
                }
                dir("v1") {
                    spec("user.spec")
                    dir("common") {
                        example("v1_shared.example")
                    }
                }
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(1)
        assertThat(results.first().examples.sharedExamples).hasSize(3)
        assertThat(results.first().examples.sharedExamples.map { it.name }).containsExactlyInAnyOrder("v1_shared.example", "api_shared.example", "root_shared.example")
    }

    @Test
    fun `should find both spec and shared examples`() {
        val structure = directoryStructure {
            spec("user.spec")
            dir("user_examples") {
                example("spec1.example")
                example("spec2.example")
            }
            dir("common") {
                example("shared1.example")
                example("shared2.example")
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(1)
        assertThat(results.first().examples.specExamples).hasSize(2)
        assertThat(results.first().examples.sharedExamples).hasSize(2)
    }

    @Test
    fun `should find examples at all levels for deeply nested spec`() {
        val structure = directoryStructure {
            dir("common") {
                example("root_common.example")
            }
            dir("api") {
                dir("api_common") {
                    example("api_level_shared.example")
                }
                dir("v2") {
                    dir("v2_common") {
                        example("v2_level_shared.example")
                    }
                    dir("users") {
                        spec("profile.spec")
                        dir("profile_examples") {
                            example("profile_spec.example")
                        }
                        dir("common") {
                            example("users_common.example")
                        }
                    }
                }
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(1)
        val profileSpec = results.first()

        assertThat(profileSpec.examples.specExamples).hasSize(1)
        assertThat(profileSpec.examples.specExamples.map { it.name }).containsExactlyInAnyOrder("profile_spec.example")
        assertThat(profileSpec.examples.sharedExamples).hasSize(4)
        assertThat(profileSpec.examples.sharedExamples.map { it.name }).containsExactlyInAnyOrder(
            "users_common.example",
            "v2_level_shared.example",
            "api_level_shared.example",
            "root_common.example"
        )
    }

    @Test
    fun `should not search outside root directory boundary`() {
        val outsideDir = tempDir.parent.resolve("outside").toFile()
        outsideDir.mkdirs()

        val outsideCommon = File(outsideDir, "common").apply { mkdirs() }
        File(outsideCommon, "outside.example").writeText("outside")

        val structure = directoryStructure {
            dir("api") {
                spec("user.spec")
            }
        }

        structure.printStructure()
        println("\nOutside Structure (should NOT be found):")
        println("  /outside/")
        println("    /common/")
        println("      outside.example")

        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(1)
        assertThat(results.first().examples.specExamples).isEmpty()
        assertThat(results.first().examples.sharedExamples).isEmpty()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `should handle spec at root directory level`() {
        val structure = directoryStructure {
            spec("root.spec")
            dir("root_examples") {
                example("example1.example")
            }
            dir("common") {
                example("shared.example")
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(1)
        assertThat(results.first().examples.specExamples).hasSize(1)
        assertThat(results.first().examples.sharedExamples).hasSize(1)
    }

    @Test
    fun `should handle empty directories gracefully`() {
        val structure = directoryStructure {
            dir("api") {
                dir("v1") {
                    // Empty directory
                }
            }
            dir("payment") {
                dir("common") {
                    // Empty common directory
                }
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).isEmpty()
    }

    @Test
    fun `should ignore non-compatible files in example directories`() {
        val structure = directoryStructure {
            spec("user.spec")
            dir("user_examples") {
                example("valid.example")
                file("readme.txt", "Not an example")
                file("data.json", "{}")
                example("also_valid.example")
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(1)
        assertThat(results.first().examples.specExamples).hasSize(2)
        assertThat(results.first().examples.specExamples.map { it.name }).containsExactlyInAnyOrder("valid.example", "also_valid.example")
    }

    @Test
    fun `should handle specs with same name in different directories`() {
        val structure = directoryStructure {
            dir("api") {
                dir("v1") {
                    spec("user.spec")
                    dir("user_examples") {
                        example("v1_example.example")
                    }
                }
                dir("v2") {
                    spec("user.spec")
                    dir("user_examples") {
                        example("v2_example.example")
                    }
                }
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(2)
        results.forEach { result ->
            assertThat(result.specFile.name).isEqualTo("user.spec")
            assertThat(result.examples.specExamples).hasSize(1)
        }

        val allExamples = results.flatMap { it.examples.specExamples.map { ex -> ex.name } }
        assertThat(allExamples).containsExactlyInAnyOrder("v1_example.example", "v2_example.example")
    }

    @Test
    fun `should correctly isolate examples for multiple specs`() {
        val structure = directoryStructure {
            dir("common") {
                example("global.example")
            }
            dir("api") {
                dir("api_common") {
                    example("api_shared.example")
                }
                spec("user.spec")
                dir("user_examples") {
                    example("user_spec.example")
                }
                spec("payment.spec")
                dir("payment_examples") {
                    example("payment_spec.example")
                }
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(2)

        val userSpec = results.find { it.specFile.name == "user.spec" }!!
        assertThat(userSpec.examples.specExamples).hasSize(1)
        assertThat(userSpec.examples.specExamples.first().name).isEqualTo("user_spec.example")
        assertThat(userSpec.examples.sharedExamples).hasSize(2)
        assertThat(userSpec.examples.sharedExamples.map { it.name }).containsExactlyInAnyOrder("api_shared.example", "global.example")

        val paymentSpec = results.find { it.specFile.name == "payment.spec" }!!
        assertThat(paymentSpec.examples.specExamples).hasSize(1)
        assertThat(paymentSpec.examples.specExamples.first().name).isEqualTo("payment_spec.example")
        assertThat(paymentSpec.examples.sharedExamples).hasSize(2)
        assertThat(paymentSpec.examples.sharedExamples.map { it.name }).containsExactlyInAnyOrder("api_shared.example", "global.example")
    }

    @Test
    fun `should handle complex real-world project structure`() {
        val structure = directoryStructure {
            dir("common") {
                example("auth.example")
                example("errors.example")
            }
            dir("api") {
                dir("common") {
                    example("api_headers.example")
                }
                dir("api_common") {
                    example("api_responses.example")
                }
                dir("v1") {
                    dir("v1_common") {
                        example("v1_pagination.example")
                    }
                    dir("users") {
                        spec("user.spec")
                        dir("user_examples") {
                            example("create_user.example")
                            example("update_user.example")
                        }
                        dir("common") {
                            example("user_validation.example")
                        }
                    }
                    dir("products") {
                        spec("product.spec")
                        dir("product_examples") {
                            example("create_product.example")
                        }
                    }
                }
                dir("v2") {
                    dir("users") {
                        spec("user.spec")
                        dir("user_examples") {
                            example("create_user_v2.example")
                        }
                    }
                }
            }
            dir("internal") {
                dir("common") {
                    example("internal_auth.example")
                }
                spec("admin.spec")
                dir("admin_examples") {
                    example("admin_action.example")
                }
            }
        }

        structure.printStructure()
        val loader = createLoader()
        val results = loader.loadAll(rootDir)

        logResults(results)
        assertThat(results).hasSize(4)

        val v1UserSpec = results.find { it.specFile.name == "user.spec" && it.specFile.absolutePath.contains("v1") }!!
        assertThat(v1UserSpec.examples.specExamples).hasSize(2)
        assertThat(v1UserSpec.examples.sharedExamples.map { it.name }).containsExactlyInAnyOrder(
            "user_validation.example",
            "v1_pagination.example",
            "api_responses.example",
            "api_headers.example",
            "auth.example",
            "errors.example"
        )

        val productSpec = results.find { it.specFile.name == "product.spec" }!!
        assertThat(productSpec.examples.specExamples).hasSize(1)
        assertThat(productSpec.examples.sharedExamples).hasSize(5)

        val v2UserSpec = results.find { it.specFile.name == "user.spec" && it.specFile.absolutePath.contains("v2") }!!
        assertThat(v2UserSpec.examples.specExamples).hasSize(1)
        assertThat(v2UserSpec.examples.sharedExamples).hasSize(4)

        val adminSpec = results.find { it.specFile.name == "admin.spec" }!!
        assertThat(adminSpec.examples.specExamples).hasSize(1)
        assertThat(adminSpec.examples.sharedExamples).hasSize(3)
    }

    private fun createLoader(): RecursiveSpecificationAndExampleLoader = RecursiveSpecificationAndExampleLoader(
        specmaticConfig = config.toSpecmaticConfig(),
        strategy = strategy
    )

    private fun directoryStructure(builder: DirectoryBuilder.() -> Unit): DirectoryBuilder {
        val dirBuilder = DirectoryBuilder(rootDir)
        dirBuilder.builder()
        return dirBuilder
    }

    private fun logResults(results: List<SpecificationWithExamples>) {
        println("\n${"=".repeat(80)}")
        println("RESULTS")
        println("=".repeat(80))
        println("Found ${results.size} specification(s)\n")

        results.forEachIndexed { index, result ->
            println("${index + 1}. Spec: ${result.specFile.relativeTo(rootDir)}")
            println("   Absolute: ${result.specFile.absolutePath}")

            if (result.examples.specExamples.isNotEmpty()) {
                println("   Spec Examples (${result.examples.specExamples.size}):")
                result.examples.specExamples.forEach { example ->
                    println("     - ${example.name}")
                }
            } else {
                println("   Spec Examples: (none)")
            }

            if (result.examples.sharedExamples.isNotEmpty()) {
                println("   Shared Examples (${result.examples.sharedExamples.size}):")
                result.examples.sharedExamples.forEach { example ->
                    println("     - ${example.name}")
                }
            } else {
                println("   Shared Examples: (none)")
            }
            println()
        }
    }

    companion object {
        private class TestLoaderStrategy : LoaderStrategy {
            override fun isCompatibleSpecification(file: File, specmaticConfig: SpecmaticConfig): Boolean = file.extension == "spec"
            override fun isCompatibleExample(file: File, specmaticConfig: SpecmaticConfig): Boolean = file.extension == "example"
        }

        private class DirectoryBuilder(private val rootDir: File) {
            fun spec(name: String) {
                File(rootDir, name).writeText("spec content: $name")
            }

            fun example(name: String) {
                File(rootDir, name).writeText("example content: $name")
            }

            fun file(name: String, content: String) {
                File(rootDir, name).writeText(content)
            }

            fun dir(name: String, builder: DirectoryBuilder.() -> Unit) {
                val subDir = File(rootDir, name).apply { mkdirs() }
                val subBuilder = DirectoryBuilder(subDir)
                subBuilder.builder()
            }

            fun printStructure() {
                println("\n${"=".repeat(80)}")
                println("DIRECTORY STRUCTURE")
                println("=".repeat(80))
                printDirectory(rootDir, "")
            }

            private fun printDirectory(dir: File, indent: String) {
                dir.listFiles()?.sortedWith(compareBy({ it.isFile }, { it.name }))?.forEach { file ->
                    when {
                        file.isDirectory -> {
                            println("$indent/${file.name}/")
                            printDirectory(file, "$indent  ")
                        }
                        file.name.endsWith(".spec") -> {
                            println("$indent${file.name} [SPEC]")
                        }
                        file.name.endsWith(".example") -> {
                            println("$indent${file.name} [EXAMPLE]")
                        }
                        else -> {
                            println("$indent${file.name}")
                        }
                    }
                }
            }
        }

        private data class TestConfig(val specExampleTemplate: String, val sharedExampleTemplates: List<String>) {
            fun toSpecmaticConfig(): SpecmaticConfig {
                return SpecmaticConfig(
                    globalSettings = SpecmaticGlobalSettings(
                        specExamplesDirectoryTemplate = specExampleTemplate,
                        sharedExamplesDirectoryTemplate = sharedExampleTemplates
                    )
                )
            }
        }
    }
}
