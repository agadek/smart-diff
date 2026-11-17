package io.github.agadek.smartdiff.engine.declarations.psi.scala

import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.languageTokens
import io.github.agadek.smartdiff.engine.declarations.psi.tokenTexts
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.ScalaLanguage

/** [text] as the Scala 2 (or Scala 3) lexer's tokens, the way the Scala provider records them. Needs the IDE. */
internal fun scalaTokens(text: String, scala3: Boolean = false): List<Token> =
    languageTokens(if (scala3) Scala3Language.INSTANCE else ScalaLanguage.INSTANCE, text)

/** Whether [rule] makes [a] and [b] the same meaningful tokens. */
internal fun sameUnder(rule: EquivalenceRuleImpl, a: String, b: String): Boolean {
    fun of(text: String) = rule.rewrite(scalaTokens(text)).filter { it.kind != TokenKind.WHITESPACE }
    return of(a) == of(b)
}

/** The meaningful texts of [code] after [rule]. */
internal fun rewrittenTexts(rule: EquivalenceRuleImpl, code: String, scala3: Boolean = false): String =
    tokenTexts(rule.rewrite(scalaTokens(code, scala3)))
