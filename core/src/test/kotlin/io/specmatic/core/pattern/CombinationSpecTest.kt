package io.specmatic.core.pattern

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CombinationSpecTest {

  @Test
  fun `error when maxCombinations less than 1`() {
    assertThatThrownBy{ CombinationSpec<Int>(mapOf(), 0) }.hasMessageContaining("must be > 0")
    assertThatThrownBy{ CombinationSpec<Int>(mapOf(), -1) }.hasMessageContaining("must be > 0")
  }

  @Test
  fun `empty list when no candidate sets supplied`() {
    val combinationSpec = CombinationSpec<Long>(mapOf(), 50)
    assertThat(
      combinationSpec.toSelectedCombinations(combinationSpec.keyToCandidates, 50)
        .map { it.value }.toList()
 ).isEmpty()
  }

  @Test
  fun `combination omitted when candidate set empty`() {
    val spec = CombinationSpec.from(mapOf("k1" to emptySequence(), "k2" to sequenceOf(21, 22)), 50)
    assertThat(
      spec.toSelectedCombinations(spec.keyToCandidates, 50)
        .map { it.value }.toList()
    ).containsExactly(mapOf("k2" to 21), mapOf("k2" to 22))
  }

  @Test
  fun `produces all combos and orders and prioritized first`() {
    val spec = CombinationSpec.from(
      mapOf(
        "k1" to sequenceOf(12L, 14L),
        "k2" to sequenceOf(25L, 22L, 28L, 27L),
        "k3" to sequenceOf(39L, 33L, 31L),
      ),
      50,
    )
    assertThat(
      spec.toSelectedCombinations(spec.keyToCandidates, 50)
        .map { it.value }.toList()
    ).containsExactly(
      // Prioritized first
      mapOf("k1" to 12, "k2" to 25, "k3" to 39),
      mapOf("k1" to 14, "k2" to 22, "k3" to 33),
      mapOf("k1" to 12, "k2" to 28, "k3" to 31),
      mapOf("k1" to 14, "k2" to 27, "k3" to 39),

      // Remaining combos
//      mapOf("k1" to 12, "k2" to 25, "k3" to 39), // Already included in prioritized list
      mapOf("k1" to 12, "k2" to 25, "k3" to 33),
      mapOf("k1" to 12, "k2" to 25, "k3" to 31),
      mapOf("k1" to 12, "k2" to 22, "k3" to 39),
      mapOf("k1" to 12, "k2" to 22, "k3" to 33),
      mapOf("k1" to 12, "k2" to 22, "k3" to 31),
      mapOf("k1" to 12, "k2" to 28, "k3" to 39),
      mapOf("k1" to 12, "k2" to 28, "k3" to 33),
//      mapOf("k1" to 12, "k2" to 28, "k3" to 31), // Already included in prioritized list
      mapOf("k1" to 12, "k2" to 27, "k3" to 39),
      mapOf("k1" to 12, "k2" to 27, "k3" to 33),
      mapOf("k1" to 12, "k2" to 27, "k3" to 31),
      mapOf("k1" to 14, "k2" to 25, "k3" to 39),
      mapOf("k1" to 14, "k2" to 25, "k3" to 33),
      mapOf("k1" to 14, "k2" to 25, "k3" to 31),
      mapOf("k1" to 14, "k2" to 22, "k3" to 39),
//      mapOf("k1" to 14, "k2" to 22, "k3" to 33), // Already included in prioritized list
      mapOf("k1" to 14, "k2" to 22, "k3" to 31),
      mapOf("k1" to 14, "k2" to 28, "k3" to 39),
      mapOf("k1" to 14, "k2" to 28, "k3" to 33),
      mapOf("k1" to 14, "k2" to 28, "k3" to 31),
//      mapOf("k1" to 14, "k2" to 27, "k3" to 39), // Already included in prioritized list
      mapOf("k1" to 14, "k2" to 27, "k3" to 33),
      mapOf("k1" to 14, "k2" to 27, "k3" to 31),
    )
  }

  @Test
  fun `restricts combos when count too high and orders with prioritized first`() {
    val spec = CombinationSpec.from(
      mapOf(
        "k1" to sequenceOf(12, 14),
        "k2" to sequenceOf(25, 22, 28, 27),
        "k3" to sequenceOf(39, 33, 31),
      ),
      23,
    )
    assertThat(
      spec.toSelectedCombinations(spec.keyToCandidates, 23)
        .map { it.value }.toList()
    ).containsExactly(
      // Prioritized first
      mapOf("k1" to 12, "k2" to 25, "k3" to 39),
      mapOf("k1" to 14, "k2" to 22, "k3" to 33),
      mapOf("k1" to 12, "k2" to 28, "k3" to 31),
      mapOf("k1" to 14, "k2" to 27, "k3" to 39),

      // Remaining combos
//      mapOf("k1" to 12, "k2" to 25, "k3" to 39),
      mapOf("k1" to 12, "k2" to 25, "k3" to 33),
      mapOf("k1" to 12, "k2" to 25, "k3" to 31),
      mapOf("k1" to 12, "k2" to 22, "k3" to 39),
      mapOf("k1" to 12, "k2" to 22, "k3" to 33),
      mapOf("k1" to 12, "k2" to 22, "k3" to 31),
      mapOf("k1" to 12, "k2" to 28, "k3" to 39),
      mapOf("k1" to 12, "k2" to 28, "k3" to 33),
//      mapOf("k1" to 12, "k2" to 28, "k3" to 31), // Already included in prioritized list
      mapOf("k1" to 12, "k2" to 27, "k3" to 39),
      mapOf("k1" to 12, "k2" to 27, "k3" to 33),
      mapOf("k1" to 12, "k2" to 27, "k3" to 31),
      mapOf("k1" to 14, "k2" to 25, "k3" to 39),
      mapOf("k1" to 14, "k2" to 25, "k3" to 33),
      mapOf("k1" to 14, "k2" to 25, "k3" to 31),
      mapOf("k1" to 14, "k2" to 22, "k3" to 39),
//      mapOf("k1" to 14, "k2" to 22, "k3" to 33), // Already included in prioritized list
      mapOf("k1" to 14, "k2" to 22, "k3" to 31),
      mapOf("k1" to 14, "k2" to 28, "k3" to 39),
      mapOf("k1" to 14, "k2" to 28, "k3" to 33),
      mapOf("k1" to 14, "k2" to 28, "k3" to 31),
//      mapOf("k1" to 14, "k2" to 27, "k3" to 39), // Already included in prioritized list
      mapOf("k1" to 14, "k2" to 27, "k3" to 33),
//      mapOf("k1" to 14, "k2" to 27, "k3" to 31), // Omitted to not exceed maxCombinations count
    )
  }

  @Test
  fun `prioritisedOnly emits only the prioritised combinations and skips the cartesian product`() {
    val candidates = mapOf(
      "k1" to sequenceOf(12L, 14L),
      "k2" to sequenceOf(25L, 22L, 28L, 27L),
      "k3" to sequenceOf(39L, 33L, 31L),
    ).mapValues { entry -> entry.value.map { HasValue(it) } }

    val spec = CombinationSpec(candidates, maxCombinations = 50, prioritisedOnly = true)

    assertThat(spec.selectedCombinations.map { it.value }.toList()).containsExactly(
      mapOf("k1" to 12L, "k2" to 25L, "k3" to 39L),
      mapOf("k1" to 14L, "k2" to 22L, "k3" to 33L),
      mapOf("k1" to 12L, "k2" to 28L, "k3" to 31L),
      mapOf("k1" to 14L, "k2" to 27L, "k3" to 39L),
    )
  }

  @Test
  fun `prioritisedOnly still respects maxCombinations`() {
    val candidates = mapOf(
      "k1" to sequenceOf(12L, 14L),
      "k2" to sequenceOf(25L, 22L, 28L, 27L),
      "k3" to sequenceOf(39L, 33L, 31L),
    ).mapValues { entry -> entry.value.map { HasValue(it) } }

    val spec = CombinationSpec(candidates, maxCombinations = 3, prioritisedOnly = true)

    assertThat(spec.selectedCombinations.map { it.value }.toList()).containsExactly(
      mapOf("k1" to 12L, "k2" to 25L, "k3" to 39L),
      mapOf("k1" to 14L, "k2" to 22L, "k3" to 33L),
      mapOf("k1" to 12L, "k2" to 28L, "k3" to 31L),
    )
  }

  @Test
  fun `restricts combos even when prioritized count is too high`() {
    val spec = CombinationSpec.from(
      mapOf(
        "k1" to sequenceOf(12, 14),
        "k2" to sequenceOf(25, 22, 28, 27),
        "k3" to sequenceOf(39, 33, 31),
      ),
      3,
    )
    assertThat(
      spec.toSelectedCombinations(spec.keyToCandidates, 3)
        .map { it.value }.toList()
    ).withFailMessage(
      spec.toSelectedCombinations(spec.keyToCandidates, 3)
        .map { it.value }.toString()
    ).containsExactly(
      // Prioritized first
      mapOf("k1" to 12, "k2" to 25, "k3" to 39),
      mapOf("k1" to 14, "k2" to 22, "k3" to 33),
      mapOf("k1" to 12, "k2" to 28, "k3" to 31),
    )
  }

}
