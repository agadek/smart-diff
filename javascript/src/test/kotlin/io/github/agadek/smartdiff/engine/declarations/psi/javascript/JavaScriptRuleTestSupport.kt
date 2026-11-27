package io.github.agadek.smartdiff.engine.declarations.psi.javascript

import com.intellij.lang.Language
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.languageTokens

/** The dialects whose lexers the rules must read alike (declarations/11 R20); ECMAScript 6 also reads JSX. */
internal enum class Dialect(val id: String) { JS("ECMAScript 6"), TS("TypeScript"), TSX("TypeScript JSX") }

/** [text] as [dialect]'s lexer tokens, the way the JavaScript provider records them. Needs the IDE. */
internal fun jsTokens(text: String, dialect: Dialect = Dialect.JS): List<Token> =
    languageTokens(Language.findLanguageByID(dialect.id) ?: error("no ${dialect.id}"), text)

/** Whether [rule] makes [a] and [b] the same meaningful tokens in [dialect]. */
internal fun sameUnder(rule: EquivalenceRuleImpl, a: String, b: String, dialect: Dialect = Dialect.JS): Boolean {
    fun of(text: String) = rule.rewrite(jsTokens(text, dialect)).filter { it.kind != TokenKind.WHITESPACE }
    return of(a) == of(b)
}
