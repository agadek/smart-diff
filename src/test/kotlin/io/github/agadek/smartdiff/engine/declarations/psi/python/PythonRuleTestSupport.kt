package io.github.agadek.smartdiff.engine.declarations.psi.python

import com.jetbrains.python.PythonLanguage
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.languageTokens
import io.github.agadek.smartdiff.engine.declarations.psi.tokenTexts

/** [text] as the Python lexer's tokens, the way the Python provider records them. Needs the IDE. */
internal fun pythonTokens(text: String): List<Token> = languageTokens(PythonLanguage.getInstance(), text)

/** Whether [rule] makes [a] and [b] the same meaningful tokens. */
internal fun sameUnder(rule: EquivalenceRuleImpl, a: String, b: String): Boolean {
    fun of(text: String) = rule.rewrite(pythonTokens(text)).filter { it.kind != TokenKind.WHITESPACE }
    return of(a) == of(b)
}

/** The meaningful texts of [code] after [rule]. */
internal fun rewrittenTexts(rule: EquivalenceRuleImpl, code: String): String = tokenTexts(rule.rewrite(pythonTokens(code)))
