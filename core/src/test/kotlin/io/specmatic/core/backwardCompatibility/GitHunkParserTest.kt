package io.specmatic.core.backwardCompatibility

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GitHunkParserTest {
    @Test
    fun `parses a single hunk with explicit counts`() {
        val diff = """
            diff --git a/spec.yaml b/spec.yaml
            index abc..def 100644
            --- a/spec.yaml
            +++ b/spec.yaml
            @@ -10,3 +12,4 @@
            -old 10
            -old 11
            -old 12
            +new 12
            +new 13
            +new 14
            +new 15
        """.trimIndent()

        val hunks = GitHunkParser.parse("spec.yaml", diff)

        assertThat(hunks.oldHunks).containsExactly(LineRange(10, 12))
        assertThat(hunks.newHunks).containsExactly(LineRange(12, 15))
    }

    @Test
    fun `parses multiple hunks`() {
        val diff = """
            diff --git a/spec.yaml b/spec.yaml
            --- a/spec.yaml
            +++ b/spec.yaml
            @@ -1 +1 @@
            -old 1
            +new 1
            @@ -5,2 +6,3 @@
            -old 5
            -old 6
            +new 6
            +new 7
            +new 8
        """.trimIndent()

        val hunks = GitHunkParser.parse("spec.yaml", diff)

        assertThat(hunks.oldHunks).containsExactly(LineRange(1, 1), LineRange(5, 6))
        assertThat(hunks.newHunks).containsExactly(LineRange(1, 1), LineRange(6, 8))
    }

    @Test
    fun `pure addition uses count zero on old side`() {
        val diff = """
            diff --git a/spec.yaml b/spec.yaml
            --- /dev/null
            +++ b/spec.yaml
            @@ -0,0 +1,5 @@
            +new 1
            +new 2
            +new 3
            +new 4
            +new 5
        """.trimIndent()

        val hunks = GitHunkParser.parse("spec.yaml", diff)

        assertThat(hunks.oldHunks).containsExactly(LineRange(0, 0))
        assertThat(hunks.newHunks).containsExactly(LineRange(1, 5))
    }

    @Test
    fun `pure deletion uses count zero on new side`() {
        val diff = """
            diff --git a/spec.yaml b/spec.yaml
            --- a/spec.yaml
            +++ /dev/null
            @@ -10,3 +9,0 @@
            -old 10
            -old 11
            -old 12
        """.trimIndent()

        val hunks = GitHunkParser.parse("spec.yaml", diff)

        assertThat(hunks.oldHunks).containsExactly(LineRange(10, 12))
        assertThat(hunks.newHunks).containsExactly(LineRange(9, 9))
    }

    @Test
    fun `defaults count to 1 when omitted`() {
        val diff = """
            diff --git a/spec.yaml b/spec.yaml
            --- a/spec.yaml
            +++ b/spec.yaml
            @@ -7 +9 @@
            -old 7
            +new 9
        """.trimIndent()

        val hunks = GitHunkParser.parse("spec.yaml", diff)

        assertThat(hunks.oldHunks).containsExactly(LineRange(7, 7))
        assertThat(hunks.newHunks).containsExactly(LineRange(9, 9))
    }

    @Test
    fun `empty input returns empty hunks`() {
        val hunks = GitHunkParser.parse("spec.yaml", "")

        assertThat(hunks.oldHunks).isEmpty()
        assertThat(hunks.newHunks).isEmpty()
        assertThat(hunks.filePath).isEqualTo("spec.yaml")
    }

    @Test
    fun `LineRange overlaps detects intersection`() {
        assertThat(LineRange(1, 5).overlaps(LineRange(4, 10))).isTrue()
        assertThat(LineRange(1, 5).overlaps(LineRange(6, 10))).isFalse()
        assertThat(LineRange(3, 4).overlaps(LineRange(1, 10))).isTrue()
    }
}
