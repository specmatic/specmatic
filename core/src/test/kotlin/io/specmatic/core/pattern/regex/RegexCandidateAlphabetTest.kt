package io.specmatic.core.pattern.regex

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class RegexCandidateAlphabetTest {
    @Test
    fun `admits every UTF16 unit except allocated markers`() {
        val source = "(?=a)(?!b)"
        val scanned = RegexScanner().scan(source) as RegexScanner.Result.Scanned
        val extracted = RegexLookaroundExtractor().extract(source, scanned) as RegexLookaroundExtraction.Extracted
        val alphabet = RegexCandidateAlphabet(extracted.assertions)
        val excluded = setOf('\uE000', '\uE001')
        val actual = (Char.MIN_VALUE..Char.MAX_VALUE).filter { alphabet.character.run(it.toString()) }
        assertThat(actual).containsExactlyElementsOf((Char.MIN_VALUE..Char.MAX_VALUE).filterNot { it in excluded })
        assertThat(alphabet.strings.run("😀")).isTrue()
        assertThat(alphabet.otherMarkerStrings('\uE000').run("\uE000")).isFalse()
        assertThat(alphabet.otherMarkerStrings('\uE000').run("\uE001")).isTrue()
    }
}
