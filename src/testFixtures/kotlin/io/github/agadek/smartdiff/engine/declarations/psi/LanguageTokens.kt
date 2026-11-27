package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.project.ProjectManager
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind

/** [text] as [language]'s lexer tokens, the way the providers record them (declarations/08, 09). Needs the IDE. */
fun languageTokens(language: Language, text: String): List<Token> {
    val definition = LanguageParserDefinitions.INSTANCE.forLanguage(language)
    val lexer = definition.createLexer(ProjectManager.getInstance().defaultProject)
    val tokens = mutableListOf<Token>()
    lexer.start(text)
    while (true) {
        val type = lexer.tokenType ?: break
        val kind = when {
            definition.whitespaceTokens.contains(type) -> TokenKind.WHITESPACE
            definition.commentTokens.contains(type) -> TokenKind.COMMENT
            else -> TokenKind.OTHER
        }
        tokens += Token(type.toString(), text.substring(lexer.tokenStart, lexer.tokenEnd), kind)
        lexer.advance()
    }
    return tokens
}

/** The meaningful token texts of [tokens], joined by single spaces, to read a rewrite in a test. */
fun tokenTexts(tokens: List<Token>): String =
    tokens.filter { it.kind != TokenKind.WHITESPACE }.joinToString(" ") { it.text }
