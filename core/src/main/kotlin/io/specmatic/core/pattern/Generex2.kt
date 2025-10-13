/*
 * Copyright 2014 y.mifrah
 *

 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.specmatic.core.pattern

import com.mifmif.common.regex.GenerexIterator
import com.mifmif.common.regex.Node
import com.mifmif.common.regex.util.Iterable
import com.mifmif.common.regex.util.Iterator
import dk.brics.automaton.Automaton
import dk.brics.automaton.RegExp
import dk.brics.automaton.State
import dk.brics.automaton.Transition
import java.util.*
import java.util.regex.Pattern

/**
 * A Java utility class that help generating string values that match a given regular expression.It generate all values
 * that are matched by the Regex, a random value, or you can generate only a specific string based on it's
 * lexicographical order .
 *
 * @author y.mifrah
 */
class Generex2 : Iterable {
    private var regExp: RegExp? = null
    private val automaton: Automaton
    private var matchedStrings: MutableList<String?> = ArrayList<String?>()
    private var rootNode: Node? = null
    private var isTransactionNodeBuilt = false

    @JvmOverloads
    constructor(regex: String, random: Random = Random()) {
        var regex = regex
        regex = requote(regex)
        regExp = createRegExp(regex)
        automaton = regExp!!.toAutomaton()
        this.random = random
    }

    @JvmOverloads
    constructor(automaton: Automaton, random: Random = Random()) {
        this.automaton = automaton
        this.random = random
    }

    /**
     * initialize the random instance used with a seed value  to generate a
     * pseudo random suite of strings based on the passed seed and matches the used regular expression
     * instance
     *
     * @param seed
     */
    fun setSeed(seed: Long) {
        random = Random(seed)
    }

    /**
     * @param indexOrder ( 1<= indexOrder <=n)
     * @return The matched string by the given pattern in the given it's order in the sorted list of matched String.<br></br>
     * `indexOrder` between 1 and `n` where `n` is the number of matched String.<br></br> If
     * indexOrder >= n , return an empty string. if there is an infinite number of String that matches the given Regex,
     * the method throws `StackOverflowError`
     */
    fun getMatchedString(indexOrder: Int): String {
        var indexOrder = indexOrder
        buildRootNode()
        if (indexOrder == 0) indexOrder = 1
        var result = buildStringFromNode(rootNode!!, indexOrder)
        result = result.substring(1, result.length - 1)
        return result
    }

    private fun buildStringFromNode(node: Node, indexOrder: Int): String {
        var indexOrder = indexOrder
        var result = ""
        var passedStringNbr: Long = 0
        val step = node.getNbrMatchedString() / node.getNbrChar()
        var usedChar = node.getMinChar()
        while (usedChar <= node.getMaxChar()) {
            passedStringNbr += step
            if (passedStringNbr >= indexOrder) {
                passedStringNbr -= step
                indexOrder -= passedStringNbr.toInt()
                result = result + "" + usedChar
                break
            }
            ++usedChar
        }
        var passedStringNbrInChildNode: Long = 0
        if (result.length == 0) passedStringNbrInChildNode = passedStringNbr
        for (childN in node.getNextNodes()) {
            passedStringNbrInChildNode += childN.getNbrMatchedString()
            if (passedStringNbrInChildNode >= indexOrder) {
                passedStringNbrInChildNode -= childN.getNbrMatchedString()
                indexOrder -= passedStringNbrInChildNode.toInt()
                result = result + buildStringFromNode(childN, indexOrder)
                break
            }
        }
        return result
    }

    val isInfinite: Boolean
        /**
         * Tells whether or not the given pattern (or `Automaton`) is infinite, that is, generates an infinite number
         * of strings.
         *
         *
         * For example, the pattern "a+" generates an infinite number of strings whether "a{5}" does not.
         *
         * @return `true` if the pattern (or `Automaton`) generates an infinite number of strings, `false`
         * otherwise
         */
        get() = !automaton.isFinite()

    val firstMatch: String
        /**
         * @return first string in lexicographical order that is matched by the given pattern.
         */
        get() {
            buildRootNode()
            var node = rootNode!!
            var result = ""
            while (node.getNextNodes().size > 0) {
                result = result + "" + node.getMinChar()
                node = node.getNextNodes().get(0)
            }
            result = result.substring(1)
            return result
        }

    /**
     * @return the number of strings that are matched by the given pattern.
     * @throws StackOverflowError if the given pattern generates a large, possibly infinite, number of strings.
     */
    fun matchedStringsSize(): Long {
        buildRootNode()
        return rootNode!!.getNbrMatchedString()
    }

    /**
     * Prepare the rootNode and it's child nodes so that we can get matchedString by index
     */
    private fun buildRootNode() {
        if (isTransactionNodeBuilt) return
        isTransactionNodeBuilt = true
        rootNode = Node()
        rootNode!!.setNbrChar(1)
        val nextNodes = prepareTransactionNodes(automaton.getInitialState())
        rootNode!!.setNextNodes(nextNodes)
        rootNode!!.updateNbrMatchedString()
    }

    private var matchedStringCounter = 0

    private fun generate(strMatch: String?, state: State, limit: Int) {
        if (matchedStringCounter == limit) return
        ++matchedStringCounter
        val transitions = state.getSortedTransitions(true)
        if (transitions.size == 0) {
            matchedStrings.add(strMatch)
            return
        }
        if (state.isAccept()) {
            matchedStrings.add(strMatch)
        }
        for (transition in transitions) {
            var c = transition.getMin()
            while (c <= transition.getMax()) {
                generate(strMatch + c, transition.getDest(), limit)
                ++c
            }
        }
    }

    /**
     * Build list of nodes that present possible transactions from the `state`.
     *
     * @param state
     * @return
     */
    private fun prepareTransactionNodes(state: State): MutableList<Node?> {
        val transactionNodes: MutableList<Node?> = ArrayList<Node?>()
        if (preparedTransactionNode == Int.Companion.MAX_VALUE / 2) return transactionNodes
        ++preparedTransactionNode

        if (state.isAccept()) {
            val acceptedNode = Node()
            acceptedNode.setNbrChar(1)
            transactionNodes.add(acceptedNode)
        }
        val transitions = state.getSortedTransitions(true)
        for (transition in transitions) {
            val trsNode = Node()
            val nbrChar = transition.getMax().code - transition.getMin().code + 1
            trsNode.setNbrChar(nbrChar)
            trsNode.setMaxChar(transition.getMax())
            trsNode.setMinChar(transition.getMin())
            val nextNodes = prepareTransactionNodes(transition.getDest())
            trsNode.setNextNodes(nextNodes)
            transactionNodes.add(trsNode)
        }
        return transactionNodes
    }

    private var preparedTransactionNode = 0
    private var random: Random

    val allMatchedStrings: MutableList<String?>
        /**
         * Generate all Strings that matches the given Regex.
         *
         * @return
         */
        get() {
            matchedStrings = ArrayList<String?>()
            generate("", automaton.getInitialState(), Int.Companion.MAX_VALUE)
            return matchedStrings
        }

    /**
     * Generate subList with a size of `limit` of Strings that matches the given Regex. the Strings are
     * ordered in lexicographical order.
     *
     * @param limit
     * @return
     */
    fun getMatchedStrings(limit: Int): MutableList<String?> {
        matchedStrings = ArrayList<String?>()
        generate("", automaton.getInitialState(), limit)
        return matchedStrings
    }

    /**
     * Generate and return a random String that match the pattern used in this Generex.
     *
     * @return
     */
    fun random(): String {
        return prepareRandom("", automaton.getInitialState(), 1, Int.Companion.MAX_VALUE)
    }

    /**
     * Generate and return a random String that match the pattern used in this Generex, and the string has a length >=
     * `minLength`
     *
     * @param minLength
     * @return
     */
    fun random(minLength: Int): String {
        return prepareRandom("", automaton.getInitialState(), minLength, Int.Companion.MAX_VALUE)
    }

    /**
     * Generate and return a random String that match the pattern used in this Generex, and the string has a length >=
     * `minLength` and <= `maxLength`
     *
     * @param minLength
     * @param maxLength
     * @return
     */
    fun random(minLength: Int, maxLength: Int): String {
        return prepareRandom("", automaton.getInitialState(), minLength, maxLength)
    }

    private tailrec fun prepareRandom(strMatch: String, state: State, minLength: Int, maxLength: Int): String {
        val transitions = state.getSortedTransitions(false)
        val selectedTransitions: MutableSet<Int?> = HashSet<Int?>()
        var result = strMatch

        var resultLength = -1
        while (transitions.size > selectedTransitions.size
            && (resultLength < minLength || resultLength > maxLength)
        ) {
            if (randomPrepared(strMatch, state, minLength, maxLength, transitions)) {
                return strMatch
            }

            val nextInt = random.nextInt(transitions.size)
            if (!selectedTransitions.contains(nextInt)) {
                selectedTransitions.add(nextInt)

                val randomTransition = transitions.get(nextInt)
                val diff = randomTransition.getMax().code - randomTransition.getMin().code + 1
                val randomOffset = if (diff > 0) random.nextInt(diff) else diff
                val randomChar = (randomOffset + randomTransition.getMin().code).toChar()

                result = prepareRandom(strMatch + randomChar, randomTransition.getDest(), minLength, maxLength)
            }
            resultLength = result.length
        }

        return result
    }

    private fun randomPrepared(
        strMatch: String,
        state: State,
        minLength: Int,
        maxLength: Int,
        transitions: MutableList<Transition>
    ): Boolean {
        if (state.isAccept()) {
            if (strMatch.length == maxLength) {
                return true
            }
            if (random.nextInt() > 0.3 * Int.Companion.MAX_VALUE && strMatch.length >= minLength) {
                return true
            }
        }

        return transitions.size == 0
    }

    override fun iterator(): Iterator {
        return GenerexIterator(automaton.getInitialState())
    }

    companion object {
        /**
         * The predefined character classes supported by `Generex`.
         *
         *
         * An immutable map containing as keys the character classes and values the equivalent regular expression syntax.
         *
         * @see .createRegExp
         */
        private val PREDEFINED_CHARACTER_CLASSES: MutableMap<String?, String?>

        init {
            val characterClasses: MutableMap<String?, String?> = HashMap<String?, String?>()
            characterClasses.put("\\\\d", "[0-9]")
            characterClasses.put("\\\\D", "[^0-9]")
            characterClasses.put("\\\\s", "[ \t\n\u000c\r]")
            characterClasses.put("\\\\S", "[^ \t\n\u000c\r]")
            characterClasses.put("\\\\w", "[a-zA-Z_0-9]")
            characterClasses.put("\\\\W", "[^a-zA-Z_0-9]")
            PREDEFINED_CHARACTER_CLASSES = Collections.unmodifiableMap<String?, String?>(characterClasses)
        }

        /**
         * Creates a `RegExp` instance from the given regular expression.
         *
         *
         * Predefined character classes are replaced with equivalent regular expression syntax prior creating the instance.
         *
         * @param regex the regular expression used to build the `RegExp` instance
         * @return a `RegExp` instance for the given regular expression
         * @throws NullPointerException if the given regular expression is `null`
         * @throws IllegalArgumentException if an error occurred while parsing the given regular expression
         * @throws StackOverflowError if the regular expression has to many transitions
         * @see .PREDEFINED_CHARACTER_CLASSES
         *
         * @see .isValidPattern
         */
        private fun createRegExp(regex: String): RegExp {
            var finalRegex = regex
            for (charClass in PREDEFINED_CHARACTER_CLASSES.entries) {
                finalRegex = finalRegex.replace(charClass.key!!.toRegex(), charClass.value!!)
            }
            return RegExp(finalRegex)
        }

        /**
         * Tells whether or not the given regular expression is a valid pattern (for `Generex`).
         *
         * @param regex the regular expression that will be validated
         * @return `true` if the regular expression is valid, `false` otherwise
         * @throws NullPointerException if the given regular expression is `null`
         */
        fun isValidPattern(regex: String): Boolean {
            try {
                createRegExp(regex)
                return true
            } catch (ignore: IllegalArgumentException) { // NOPMD - Not valid.
            } catch (ignore: StackOverflowError) { // NOPMD - Possibly valid but stack not big enough to handle it.
            }
            return false
        }

        /**
         * Requote a regular expression by escaping some parts of it from generation without need to escape each special
         * character one by one. <br></br> this is done by setting the part to be interpreted as normal characters (thus, quote
         * all meta-characters) between \Q and \E , ex : <br></br> ` minion_\d{3}\Q@gru.evil\E ` <br></br> will be
         * transformed to : <br></br> ` minion_\d{3}\@gru\.evil `
         *
         * @param regex
         * @return
         */
        private fun requote(regex: String): String {
            val patternRequoted = Pattern.compile("\\\\Q(.*?)\\\\E")
            // http://stackoverflow.com/questions/399078/what-special-characters-must-be-escaped-in-regular-expressions
            // adding "@" prevents StackOverflowError inside generex: https://github.com/mifmif/Generex/issues/21
            val patternSpecial = Pattern.compile("[.^$*+?(){|\\[\\\\@]")
            val sb = StringBuilder(regex)
            val matcher = patternRequoted.matcher(sb)
            while (matcher.find()) {
                sb.replace(
                    matcher.start(),
                    matcher.end(),
                    patternSpecial.matcher(matcher.group(1)).replaceAll("\\\\$0")
                )
                //matcher.reset();
            }
            return sb.toString()
        }
    }
}
