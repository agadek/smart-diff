package io.github.agadek.smartdiff.engine.declarations.psi.kotlin

import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.languageTokens
import io.github.agadek.smartdiff.engine.declarations.psi.tokenTexts
import org.jetbrains.kotlin.idea.KotlinLanguage

/** [text] as the Kotlin lexer's tokens, the way the Kotlin provider records them. Needs the IDE. */
internal fun kotlinTokens(text: String): List<Token> = languageTokens(KotlinLanguage.INSTANCE, text)

/** Whether [rule] makes [a] and [b] the same tokens. */
internal fun sameUnder(rule: EquivalenceRuleImpl, a: String, b: String): Boolean =
    rule.rewrite(kotlinTokens(a)).filter { it.kind != TokenKind.WHITESPACE } ==
        rule.rewrite(kotlinTokens(b)).filter { it.kind != TokenKind.WHITESPACE }

/** The meaningful texts of [code] after [rule]. */
internal fun rewrittenTexts(rule: EquivalenceRuleImpl, code: String): String = tokenTexts(rule.rewrite(kotlinTokens(code)))
