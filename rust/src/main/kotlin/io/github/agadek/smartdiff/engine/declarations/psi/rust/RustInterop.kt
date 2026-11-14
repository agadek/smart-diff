package io.github.agadek.smartdiff.engine.declarations.psi.rust

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.TextRange
import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsBlockFields
import org.rust.lang.core.psi.RsConstant
import org.rust.lang.core.psi.RsEnumBody
import org.rust.lang.core.psi.RsEnumItem
import org.rust.lang.core.psi.RsEnumVariant
import org.rust.lang.core.psi.RsForeignModItem
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsImplItem
import org.rust.lang.core.psi.RsMacro
import org.rust.lang.core.psi.RsMacro2
import org.rust.lang.core.psi.RsMacroCall
import org.rust.lang.core.psi.RsMembers
import org.rust.lang.core.psi.RsModDeclItem
import org.rust.lang.core.psi.RsModItem
import org.rust.lang.core.psi.RsNamedFieldDecl
import org.rust.lang.core.psi.RsSelfParameter
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.RsTraitItem
import org.rust.lang.core.psi.RsTypeAlias
import org.rust.lang.core.psi.RsValueParameter
import org.rust.lang.core.psi.ext.RsConstantKind
import org.rust.lang.core.psi.ext.RsStructKind
import org.rust.lang.core.psi.ext.isNegativeImpl
import org.rust.lang.core.psi.ext.kind
import org.rust.lang.core.psi.ext.selfParameter
import org.rust.lang.core.psi.ext.valueParameters

/**
 * The only place that touches the Rust plugin's PSI (languages/05-rust-details §3). Every entry point the provider
 * calls is wrapped so a [LinkageError] from the plugin becomes a plain exception the engine turns into a failed result.
 */
internal object RustInterop {

    data class Decl(
        val element: PsiElement,
        val kind: String,
        val name: String,
        val signatureKey: String?,
        val signatureDisplay: String?,
        val members: List<PsiElement>,
        val body: PsiElement?,
        val range: TextRange? = null,
    )

    private val RUST: Language
        get() = Language.findLanguageByID("Rust") ?: error("the Rust language is not registered")

    fun parse(project: Project, text: CharSequence): PsiFile = link {
        PsiFileFactory.getInstance(project).createFileFromText("file.rs", RUST, text, false, false)
            ?: error("PsiFileFactory returned null for Rust")
    }

    fun topLevel(file: PsiFile): List<PsiElement> = link {
        expandItems(PsiTreeUtil.getChildrenOfTypeAsList(file, PsiElement::class.java))
    }

    fun children(element: PsiElement): List<PsiElement> = link {
        when (element) {
            is RsStructItem -> element.blockFields?.namedFieldDeclList.orEmpty<PsiElement>()
            is RsEnumItem -> element.enumBody?.enumVariantList.orEmpty<PsiElement>()
            is RsEnumVariant -> element.blockFields?.namedFieldDeclList.orEmpty<PsiElement>()
            is RsTraitItem -> traitMembers(element.members)
            is RsImplItem -> implMembers(element.members)
            is RsModItem -> {
                val block = PsiTreeUtil.getChildOfType(element, RsBlock::class.java)
                if (block != null) expandItems(PsiTreeUtil.getChildrenOfTypeAsList(block, PsiElement::class.java))
                else expandItems(PsiTreeUtil.getChildrenOfTypeAsList(element, PsiElement::class.java))
            }
            else -> emptyList()
        }
    }

    fun decl(element: PsiElement): Decl? = link {
        when (element) {
            is RsFunction -> functionDecl(element)
            is RsStructItem -> {
                val kind = if (isUnion(element)) "UNION" else "STRUCT"
                val fields: List<PsiElement> = element.blockFields?.namedFieldDeclList.orEmpty()
                Decl(element, kind, element.name.orEmpty(), null, null, fields, element.blockFields)
            }
            is RsEnumItem -> {
                val variants: List<PsiElement> = element.enumBody?.enumVariantList.orEmpty()
                Decl(element, "ENUM", element.name.orEmpty(), null, null, variants, element.enumBody)
            }
            is RsEnumVariant -> variantDecl(element)
            is RsTraitItem -> Decl(element, "TRAIT", element.name.orEmpty(), null, null, traitMembers(element.members), element.members)
            is RsImplItem -> implDecl(element)
            is RsModItem -> {
                val block = PsiTreeUtil.getChildOfType(element, RsBlock::class.java)
                val members = if (block != null) {
                    expandItems(PsiTreeUtil.getChildrenOfTypeAsList(block, PsiElement::class.java))
                } else {
                    expandItems(PsiTreeUtil.getChildrenOfTypeAsList(element, PsiElement::class.java))
                }
                Decl(element, "MODULE", element.name.orEmpty(), null, null, members, block)
            }
            is RsModDeclItem -> Decl(element, "MODULE", element.name.orEmpty(), null, null, emptyList(), null)
            is RsTypeAlias -> Decl(element, "TYPE_ALIAS", element.name.orEmpty(), null, null, emptyList(), null)
            is RsConstant -> {
                val kind = if (isConst(element)) "CONSTANT" else "STATIC"
                Decl(element, kind, element.name.orEmpty(), null, null, emptyList(), null)
            }
            is RsMacro -> macroDecl(element)
            is RsMacro2 -> macroDecl(element)
            is RsNamedFieldDecl -> {
                Decl(element, "FIELD", element.name.orEmpty(), null, null, emptyList(), null)
            }
            is RsForeignModItem -> null
            else -> null
        }
    }

    /** Token ranges inside macro-call arguments that carry meaning (languages/05-rust-details §10 R1). */
    fun significantWhitespace(file: PsiFile): List<TextRange> = link {
        val ranges = mutableListOf<TextRange>()
        PsiTreeUtil.processElements(file) { element ->
            if (element is RsMacroCall) {
                val args = macroArguments(element)
                for (arg in args) {
                    ranges += TextRange(arg.textRange.startOffset, arg.textRange.endOffset)
                }
            }
            true
        }
        ranges
    }

    private fun macroArguments(call: RsMacroCall): List<PsiElement> = buildList {
        call.macroArgument?.let { add(it) }
        val specific = listOfNotNull(
            call.formatMacroArgument,
            call.assertMacroArgument,
            call.assertEqMacroArgument,
            call.exprMacroArgument,
            call.vecMacroArgument,
            call.includeMacroArgument,
            call.concatMacroArgument,
            call.envMacroArgument,
            call.asmMacroArgument,
        )
        for (arg in specific) if (arg !in this) add(arg)
    }

    private fun functionDecl(function: RsFunction): Decl? {
        if (PsiTreeUtil.getParentOfType(function, RsBlock::class.java) != null) return null
        val body = PsiTreeUtil.getChildOfType(function, RsBlock::class.java)
        if (function.valueParameterList == null) {
            return Decl(function, "FUNCTION", function.name.orEmpty(), "", "", emptyList(), body)
        }
        val key = paramsKey(function)
        val display = paramsDisplay(function)
        return Decl(function, "FUNCTION", function.name.orEmpty(), key, display, emptyList(), body)
    }

    private fun paramsDisplay(function: RsFunction): String {
        val parts = mutableListOf<String>()
        function.selfParameter?.let { parts += selfDisplay(it) }
        for (param in function.valueParameters) {
            parts += param.typeReference?.text?.trim().orEmpty().ifEmpty { "_" }
        }
        return parts.joinToString(", ")
    }

    private fun paramsKey(function: RsFunction): String {
        val parts = mutableListOf<String>()
        function.selfParameter?.let { parts += selfKey(it) }
        for (param in function.valueParameters) {
            parts += param.typeReference?.text ?: "_"
        }
        return parts.joinToString(",").replace(Regex("\\s+"), "")
    }

    private fun selfDisplay(self: RsSelfParameter): String = buildString {
        if (self.and != null) append("&")
        self.lifetime?.let { append(it.text).append(" ") }
        if (self.mut != null) append(if (length == 0 || last() == ' ' || last() == '&') "mut " else " mut ")
        val type = self.typeReference
        if (type != null) append(type.text?.trim().orEmpty()) else append("self")
    }.trim()

    private fun selfKey(self: RsSelfParameter): String = buildString {
        if (self.and != null) append('&')
        self.lifetime?.let { append(it.text).append(' ') }
        if (self.mut != null) append(if (isEmpty() || last() == ' ') "mut " else " mut ")
        val type = self.typeReference
        if (type != null) append(type.text) else append("self")
    }.trim()

    private fun variantDecl(variant: RsEnumVariant): Decl {
        val block = variant.blockFields
        val tuple = variant.tupleFields
        val sig = tuple?.text ?: if (block != null) "{…}" else null
        val fields: List<PsiElement> = block?.namedFieldDeclList.orEmpty()
        val body: PsiElement? = block ?: tuple
        return Decl(variant, "VARIANT", variant.name.orEmpty(), sig, sig, fields, body)
    }


    private fun isUnion(struct: RsStructItem): Boolean = try {
        struct.kind == RsStructKind.UNION
    } catch (_: Throwable) {
        struct.node.getChildren(null).any { it.text == "union" }
    }

    private fun isConst(constant: RsConstant): Boolean = try {
        constant.kind == RsConstantKind.CONST
    } catch (_: Throwable) {
        constant.node.getChildren(null).any { it.text == "const" }
    }

    private fun implDecl(impl: RsImplItem): Decl {
        val type = impl.typeReference?.text.orEmpty()
        val trait = impl.traitRef?.text
        val name = when {
            trait != null && impl.isNegativeImpl -> "!$trait for $type"
            trait != null -> "$trait for $type"
            else -> type
        }.trim()
        return Decl(impl, "IMPL", name, null, null, implMembers(impl.members), impl.members)
    }

    private fun macroDecl(element: PsiElement): Decl {
        return Decl(element, "MACRO", (element as? com.intellij.psi.PsiNamedElement)?.name.orEmpty(), null, null, emptyList(), null, null)
    }

    private fun traitMembers(members: RsMembers?): List<PsiElement> = members?.children?.filter(::isImplMemberElement).orEmpty()

    private fun implMembers(members: RsMembers?): List<PsiElement> = members?.children?.filter(::isImplMemberElement).orEmpty()

    private fun isImplMemberElement(element: PsiElement): Boolean = element is RsFunction || element is RsTypeAlias || element is RsConstant

    private fun isItem(element: PsiElement): Boolean = when (element) {
        is RsFunction, is RsStructItem, is RsEnumItem, is RsTraitItem, is RsImplItem,
        is RsModItem, is RsModDeclItem, is RsTypeAlias, is RsConstant, is RsMacro, is RsMacro2 -> true
        else -> false
    }

    private fun expandItems(elements: List<PsiElement>): List<PsiElement> = buildList {
        for (element in elements) {
            if (element is RsForeignModItem) {
                addAll(element.children.filter(::isForeignItem))
            } else if (isItem(element)) {
                add(element)
            }
        }
    }

    private fun isForeignItem(element: PsiElement): Boolean = when (element) {
        is RsFunction, is RsConstant, is RsTypeAlias, is RsMacro, is RsMacro2 -> true
        else -> false
    }

    private fun collectSignificant(element: PsiElement, out: MutableList<TextRange>) {
        var child = element.firstChild
        while (child != null) {
            when (child) {
                is PsiComment -> out += TextRange(child.textRange.startOffset, child.textRange.endOffset)
                is PsiWhiteSpace -> if (child.textLength > 0) {
                    out += TextRange(child.textRange.startOffset, child.textRange.endOffset)
                }
                else -> collectSignificant(child, out)
            }
            child = child.nextSibling
        }
    }

    private inline fun <T> link(block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        throw IllegalStateException("Rust plugin PSI unavailable: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}
