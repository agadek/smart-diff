package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.LanguageIds
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/** C#, with `tree-sitter-c-sharp` (engines/05 R9, R13, R15 to R17). */
internal object CSharpLanguage : TreeSitterLanguage {
    override val id = LanguageIds.TREE_SITTER_C_SHARP
    override val grammar = TreeSitterGrammar.C_SHARP
    override val whitespacePolicy = WhitespacePolicy.INSIGNIFICANT
    override val comments = setOf("comment")
    override val strings = setOf(
        "string_literal", "verbatim_string_literal", "raw_string_literal", "interpolated_string_expression", "character_literal",
    )

    private val opaque = setOf(
        "block", "arrow_expression_clause", "accessor_list", "global_statement", "lambda_expression",
        "anonymous_method_expression", "parameter_list", "bracketed_parameter_list",
    )

    override fun isOpaque(type: String) = type in opaque

    override fun isDocComment(text: String) = text.startsWith("///") || (text.startsWith("/**") && !text.startsWith("/**/"))

    override fun describe(node: Int, syntax: TreeSitterSyntax, inType: Boolean): List<Found>? = with(syntax) {
        val name = field(node, "name")?.let { normalized(start(it), end(it)) }
        val found = when (type(node)) {
            "namespace_declaration" ->
                Found(DeclarationKind.NAMESPACE, name ?: return null, null, members = field(node, "body"), membersInType = false)
            // R9: a file-scoped namespace holds the declarations after it.
            "file_scoped_namespace_declaration" ->
                Found(DeclarationKind.NAMESPACE, name ?: return null, null, membersInType = false, ownsFollowingSiblings = true)
            "class_declaration", "struct_declaration", "record_declaration", "interface_declaration", "enum_declaration" -> {
                val kind = when (type(node)) {
                    "class_declaration" -> DeclarationKind.CLASS
                    "struct_declaration" -> DeclarationKind.STRUCT
                    "record_declaration" -> DeclarationKind.RECORD
                    "interface_declaration" -> DeclarationKind.INTERFACE
                    else -> DeclarationKind.ENUM
                }
                Found(kind, name ?: return null, null, members = field(node, "body"))
            }
            "enum_member_declaration" -> Found(DeclarationKind.ENUM_CONSTANT, name ?: return null, null)
            "delegate_declaration" -> Found(DeclarationKind.DELEGATE, name ?: return null, signature(field(node, "parameters"), syntax))
            "method_declaration" -> Found(DeclarationKind.METHOD, name ?: return null, signature(field(node, "parameters"), syntax), body = body(node, syntax))
            "constructor_declaration" ->
                Found(DeclarationKind.CONSTRUCTOR, name ?: return null, signature(field(node, "parameters"), syntax), body = body(node, syntax))
            "destructor_declaration" -> Found(DeclarationKind.DESTRUCTOR, "~" + (name ?: return null), null, body = body(node, syntax))
            "operator_declaration" -> {
                val operator = field(node, "operator")?.let(::text) ?: return null
                Found(DeclarationKind.OPERATOR, "operator $operator", signature(field(node, "parameters"), syntax), body = body(node, syntax))
            }
            "conversion_operator_declaration" -> {
                val keyword = children(node).firstOrNull { !isNamed(it) && text(it) in setOf("implicit", "explicit") }?.let(::text)
                val target = field(node, "type")?.let { TreeSitterTables.typeText(syntax, it) } ?: return null
                val operatorName = listOfNotNull(keyword, "operator", target).joinToString(" ")
                Found(DeclarationKind.OPERATOR, operatorName, signature(field(node, "parameters"), syntax), body = body(node, syntax))
            }
            "property_declaration" -> Found(DeclarationKind.PROPERTY, name ?: return null, null)
            "indexer_declaration" -> Found(DeclarationKind.INDEXER, "this", signature(field(node, "parameters"), syntax))
            "event_declaration" -> Found(DeclarationKind.EVENT, name ?: return null, null)
            "field_declaration", "event_field_declaration" -> return variables(node, syntax)
            else -> return null
        }
        listOf(found)
    }

    /** One field or event per variable in `int a = 1, b;` (R9). */
    private fun variables(node: Int, syntax: TreeSitterSyntax): List<Found>? = with(syntax) {
        val declaration = child(node, "variable_declaration") ?: return null
        val declarators = children(declaration).filter { type(it) == "variable_declarator" }
        if (declarators.isEmpty()) return null
        val kind = if (type(node) == "event_field_declaration") DeclarationKind.EVENT else DeclarationKind.FIELD
        TreeSitterTables.split(syntax, node, declarators, commaParent = declaration).mapIndexed { index, range ->
            val name = field(declarators[index], "name")?.let(::text) ?: text(declarators[index])
            Found(kind, name, null, range = range)
        }
    }

    /**
     * The parameter types with their `ref`, `out`, `in`, `this` and `params` modifiers, which tell overloads apart,
     * and without names and default values (R16).
     */
    private fun signature(parameters: Int?, syntax: TreeSitterSyntax): String = with(syntax) {
        if (parameters == null) return ""
        val types = mutableListOf<String>()
        val pending = mutableListOf<String>()
        for (child in children(parameters)) {
            when {
                type(child) == "parameter" -> {
                    val typeNode = field(child, "type")
                    val modifiers = children(child).takeWhile { it != typeNode }
                        .filter { type(it) != "attribute_list" && field(it) != "name" }
                        .map { TreeSitterTables.compact(text(it)) }
                    types += (modifiers + listOfNotNull(typeNode?.let { TreeSitterTables.typeText(syntax, it) })).joinToString(" ")
                }
                // The grammar puts a `params` parameter's parts directly in the list.
                !isNamed(child) && text(child) == "params" -> pending += "params"
                field(child) == "type" -> {
                    types += (pending + TreeSitterTables.typeText(syntax, child)).joinToString(" ")
                    pending.clear()
                }
            }
        }
        types.joinToString(", ")
    }

    /** A block body's inside, or an expression body. */
    private fun body(node: Int, syntax: TreeSitterSyntax) = syntax.field(node, "body")?.let { TreeSitterTables.braced(syntax, it) }
}
