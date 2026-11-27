package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.intellij.lang.Language
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.languageTokens

/** [text] as the Go lexer's tokens, the way the Go provider records them. Needs the IDE. */
internal fun goTokens(text: String): List<Token> = languageTokens(Language.findLanguageByID("go") ?: error("no Go"), text)

/** Whether [rule] makes [a] and [b] the same tokens, line breaks counted as the Go provider counts them. */
internal fun sameUnder(rule: EquivalenceRuleImpl, a: String, b: String): Boolean {
    fun of(text: String) = rule.rewrite(goTokens(text)).mapNotNull { token ->
        when {
            token.kind != TokenKind.WHITESPACE -> token.type to token.text
            token.text.contains('\n') -> "LINE_BREAK" to "⏎"
            else -> null
        }
    }.fold(mutableListOf<Pair<String, String>>()) { acc, t -> if (!(t.first == "LINE_BREAK" && acc.lastOrNull()?.first == "LINE_BREAK")) acc += t; acc }
    return of(a) == of(b)
}
