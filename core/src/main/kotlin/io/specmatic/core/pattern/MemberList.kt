package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.utilities.withNullPattern

data class MemberList(private val members: List<Pattern>) {
    fun getEncompassableList(count: Int, resolver: Resolver): List<Pattern> {
        if (count > members.size)
            throw ContractException("The lengths of the expected and actual array patterns don't match.")

        return resolvePatterns(members, resolver)
    }

    private fun resolvePatterns(pattern: List<Pattern>, resolver: Resolver): List<Pattern> {
        val resolverWithNullType = withNullPattern(resolver)
        return pattern.map { resolvedHop(it, resolverWithNullType) }
    }

    fun getEncompassables(resolver: Resolver): List<Pattern> =
        members.map { resolvedHop(it, resolver) }

    fun patternList(): List<Pattern> = members
}
