package io.github.agadek.smartdiff.engine.declarations.model

import io.github.agadek.smartdiff.engine.api.EquivalenceRule

/**
 * The implementation of one [EquivalenceRule] (declarations/08): a pure token function that writes every rewrite the
 * rule names in one canonical form, so two sources that differ only in such rewrites have equal tokens afterwards. It
 * never changes a token's [Token.type] or [Token.kind] and runs after classification, on a declaration's own tokens.
 */
interface EquivalenceRuleImpl {
    val rule: EquivalenceRule

    fun rewrite(tokens: List<Token>): List<Token>
}
