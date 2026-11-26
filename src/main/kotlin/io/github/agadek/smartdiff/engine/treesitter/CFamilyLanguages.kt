package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.LanguageIds
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/** C, with `tree-sitter-c` (engines/05 R10, R13, R14, R16 to R18). */
internal object CLanguage : CFamilyLanguage(LanguageIds.TREE_SITTER_C, TreeSitterGrammar.C)

/** C++, with `tree-sitter-cpp`: the C declarations and namespaces, classes, templates and aliases (R11, R12). */
internal object CppLanguage : CFamilyLanguage(LanguageIds.TREE_SITTER_CPP, TreeSitterGrammar.CPP)

/**
 * The declaration table that C and C++ share (engines/05-tree-sitter-details §5). The C++ node types never occur in
 * a C tree, so one table serves both grammars.
 */
internal abstract class CFamilyLanguage(override val id: String, override val grammar: TreeSitterGrammar) : TreeSitterLanguage {
    override val whitespacePolicy = WhitespacePolicy.INSIGNIFICANT
    override val comments = setOf("comment")
    override val strings = setOf("string_literal", "char_literal", "raw_string_literal")

    /** A macro's body is one leaf; its words are compared and its spacing is not, except for line breaks (§7). */
    override val splitLeaves = setOf("preproc_arg")

    private val opaque = setOf(
        "compound_statement", "lambda_expression", "initializer_list", "field_initializer_list", "parameter_list",
        "argument_list", "template_argument_list", "friend_declaration",
    )

    override fun isOpaque(type: String) = type in opaque

    /** engines/06: macro patterns read as blank space. */
    override val recovers = true

    override fun recover(text: CharSequence): MacroRecovery? = CMacroRecovery.recover(text)

    override fun isDocComment(text: String) = text.startsWith("///") || text.startsWith("//!") ||
        (text.startsWith("/**") && !text.startsWith("/**/")) || text.startsWith("/*!")

    /** A struct, union, enum or class written as `struct S { … };` ends after its `;` (R15). */
    override fun declarationEnd(node: Int, syntax: TreeSitterSyntax): Int = with(syntax) {
        val parent = tree.parent(node).takeIf { it >= 0 } ?: return end(node)
        val siblings = children(parent)
        val next = siblings.getOrNull(siblings.indexOf(node) + 1)
        if (next != null && !isNamed(next) && type(next) == ";" && start(next) == end(node)) end(next) else end(node)
    }

    /**
     * The line break that ends each directive, and every backslash line continuation inside one (R18): a directive
     * starts at its `#` keyword and runs to the first line break without a backslash before it. Directive lines that
     * macro recovery read as blank space are not in the tree; their line breaks come from the recovery (engines/06 R18).
     */
    override fun significantLineBreaks(syntax: TreeSitterSyntax): Set<Int> = with(syntax) {
        val breaks = HashSet<Int>(recovery?.lineBreaks.orEmpty())
        var scanned = 0
        for (node in 0 until tree.size) {
            val directive = (!isNamed(node) && type(node).startsWith("#")) || type(node) == "preproc_directive"
            if (!directive || start(node) < scanned) continue
            var at = start(node)
            while (at < text.length) {
                if (text[at] == '\n') {
                    breaks += at
                    var before = at - 1
                    if (before >= 0 && text[before] == '\r') before--
                    if (before < 0 || text[before] != '\\') break
                }
                at++
            }
            scanned = at + 1
        }
        breaks
    }

    override fun describe(node: Int, syntax: TreeSitterSyntax, inType: Boolean): List<Found>? = with(syntax) {
        when (type(node)) {
            "function_definition" -> function(node, syntax, inType, hasBody = true)?.let { listOf(it) }
            "declaration" -> declaration(node, syntax, inType, if (inType) DeclarationKind.FIELD else DeclarationKind.VARIABLE)
            "field_declaration" -> declaration(node, syntax, inType, DeclarationKind.FIELD)
            "struct_specifier", "union_specifier", "enum_specifier", "class_specifier" -> typeSpecifier(node, syntax)?.let { listOf(it) }
            "enumerator" -> field(node, "name")?.let { listOf(Found(DeclarationKind.ENUM_CONSTANT, text(it), null)) }
            "type_definition" -> typeDefinition(node, syntax)
            "preproc_def", "preproc_function_def" ->
                field(node, "name")?.let { listOf(Found(DeclarationKind.MACRO, text(it), null)) }
            "namespace_definition" -> {
                val name = field(node, "name")?.let { normalized(start(it), end(it)) } ?: "(anonymous)"
                listOf(Found(DeclarationKind.NAMESPACE, name, null, members = field(node, "body"), membersInType = false))
            }
            "alias_declaration" -> field(node, "name")?.let { listOf(Found(DeclarationKind.TYPE_ALIAS, text(it), null)) }
            "template_declaration" -> {
                // The wrapped declaration's kind with the template's range; the parameters are its own tokens.
                val inner = namedChildren(node).lastOrNull { type(it) !in TEMPLATE_PARTS } ?: return null
                describe(inner, syntax, inType)
            }
            else -> null
        }
    }

    /** A struct, union, enum or class with a name or a body; an unnamed one with a body is `(anonymous)`. */
    private fun typeSpecifier(node: Int, syntax: TreeSitterSyntax): Found? = with(syntax) {
        val body = field(node, "body")
        val name = field(node, "name")?.let { TreeSitterTables.compact(text(it)) } ?: if (body != null) "(anonymous)" else return null
        val kind = when (type(node)) {
            "struct_specifier" -> DeclarationKind.STRUCT
            "union_specifier" -> DeclarationKind.UNION
            "enum_specifier" -> DeclarationKind.ENUM
            else -> DeclarationKind.CLASS
        }
        Found(kind, name, null, members = body)
    }

    /** `typedef … Name;`, one declaration per name; a `typedef struct { … } Name` holds the struct's members. */
    private fun typeDefinition(node: Int, syntax: TreeSitterSyntax): List<Found>? = with(syntax) {
        val declarators = fields(node, "declarator")
        if (declarators.isEmpty()) return null
        val members = field(node, "type")?.let { field(it, "body") }
        TreeSitterTables.split(syntax, node, declarators).mapIndexed { index, range ->
            Found(DeclarationKind.TYPE_ALIAS, declaratorName(declarators[index], syntax), null, range = range, members = members.takeIf { index == 0 })
        }
    }

    /**
     * A `declaration` or `field_declaration`: a function prototype or method, the type it only defines, as in
     * `struct Inner { … };` inside a class, or one [variable] per declarator.
     */
    private fun declaration(node: Int, syntax: TreeSitterSyntax, inType: Boolean, variable: DeclarationKind): List<Found>? = with(syntax) {
        val declarators = fields(node, "declarator")
        if (declarators.isEmpty()) return field(node, "type")?.takeIf { field(it, "body") != null }?.let { typeSpecifier(it, syntax) }?.let { listOf(it) }
        if (declarators.size == 1 && functionDeclarator(declarators[0], syntax) != null) {
            return function(node, syntax, inType, hasBody = false)?.let { listOf(it) }
        }
        TreeSitterTables.split(syntax, node, declarators).mapIndexed { index, range ->
            Found(variable, declaratorName(declarators[index], syntax), null, range = range)
        }
    }

    /** A function, method, constructor or destructor, with its parameter types as the signature (R12, R16). */
    private fun function(node: Int, syntax: TreeSitterSyntax, inType: Boolean, hasBody: Boolean): Found? = with(syntax) {
        val declarator = functionDeclarator(field(node, "declarator") ?: return null, syntax) ?: return null
        val name: String
        val parameters: Int?
        val kind: DeclarationKind
        if (type(declarator) == "operator_cast") {
            name = "operator " + (field(declarator, "type")?.let { TreeSitterTables.typeText(syntax, it) } ?: "")
            parameters = field(declarator, "declarator")?.let { field(it, "parameters") }
            kind = if (inType) DeclarationKind.METHOD else DeclarationKind.FUNCTION
        } else {
            val nameNode = field(declarator, "declarator") ?: return null
            name = nameText(nameNode, syntax)
            parameters = field(declarator, "parameters")
            val last = lastSegment(nameNode, syntax)
            val qualified = type(nameNode) == "qualified_identifier"
            val untyped = field(node, "type") == null
            kind = when {
                type(last) == "destructor_name" -> DeclarationKind.DESTRUCTOR
                untyped && inType && type(nameNode) == "identifier" -> DeclarationKind.CONSTRUCTOR
                untyped && qualified && text(last) == scopeName(nameNode, syntax) -> DeclarationKind.CONSTRUCTOR
                inType -> DeclarationKind.METHOD
                else -> DeclarationKind.FUNCTION
            }
        }
        val body = if (hasBody) field(node, "body")?.takeIf { type(it) == "compound_statement" }?.let { TreeSitterTables.braced(syntax, it) } else null
        Found(kind, name, signature(parameters, syntax), body = body)
    }

    /**
     * The function declarator under [declarator], through pointer and reference return types, or null when
     * [declarator] declares no function, as for a function pointer variable `int (*f)(int)`.
     */
    private fun functionDeclarator(declarator: Int, syntax: TreeSitterSyntax): Int? = with(syntax) {
        when (type(declarator)) {
            "function_declarator" -> declarator.takeIf { field(it, "declarator")?.let(::type) in FUNCTION_NAMES }
            "operator_cast" -> declarator
            "pointer_declarator", "reference_declarator", "attributed_declarator" ->
                inner(declarator, syntax)?.let { functionDeclarator(it, syntax) }
            else -> null
        }
    }

    /** The declarator inside [declarator]: its `declarator` field, or its first named child. */
    private fun inner(declarator: Int, syntax: TreeSitterSyntax): Int? = with(syntax) {
        field(declarator, "declarator") ?: namedChildren(declarator).firstOrNull { type(it) !in DECLARATOR_EXTRAS }
    }

    /** The name a declarator declares: `p` in `*p`, `a` in `a[3] = {}`, `f` in `(*f)(int)`. */
    private fun declaratorName(declarator: Int, syntax: TreeSitterSyntax): String = with(syntax) {
        var at = declarator
        while (type(at) !in NAMES) at = inner(at, syntax) ?: break
        nameText(at, syntax)
    }

    private fun nameText(node: Int, syntax: TreeSitterSyntax): String =
        TreeSitterTables.compact(syntax.text(node)).replace(OPERATOR_SPACE, "operator").replace("~ ", "~")

    /** The last part of a qualified name, `~Cart` in `Cart::~Cart`. */
    private fun lastSegment(node: Int, syntax: TreeSitterSyntax): Int = with(syntax) {
        var at = node
        while (type(at) == "qualified_identifier") at = field(at, "name") ?: break
        at
    }

    /** The type a qualified name's last part is in, without template arguments: `Box` in `Box<T>::Box`. */
    private fun scopeName(node: Int, syntax: TreeSitterSyntax): String? = with(syntax) {
        var at = node
        var scope: Int? = null
        while (type(at) == "qualified_identifier") {
            scope = field(at, "scope")
            at = field(at, "name") ?: break
        }
        scope?.let { text(it).substringBefore('<').trim() }
    }

    /** The parameter types without names and default values, `(void)` as `()` (R16). */
    private fun signature(parameters: Int?, syntax: TreeSitterSyntax): String = with(syntax) {
        if (parameters == null) return ""
        val types = children(parameters).mapNotNull { parameter ->
            when {
                type(parameter) in PARAMETERS -> {
                    val excluded = buildSet {
                        field(parameter, "declarator")?.let { declarator ->
                            var at = declarator
                            while (type(at) !in NAMES) at = inner(at, syntax) ?: break
                            if (type(at) in NAMES) add(at)
                        }
                        field(parameter, "default_value")?.let(::add)
                        children(parameter).filter { !isNamed(it) && type(it) == "=" }.forEach(::add)
                    }
                    TreeSitterTables.typeText(syntax, parameter, excluded)
                }
                type(parameter) == "variadic_parameter" || (!isNamed(parameter) && type(parameter) == "...") -> "..."
                else -> null
            }
        }
        if (types == listOf("void")) "" else types.joinToString(", ")
    }

    private companion object {
        val TEMPLATE_PARTS = setOf("template_parameter_list", "comment", "requires_clause")
        val PARAMETERS = setOf("parameter_declaration", "optional_parameter_declaration", "variadic_parameter_declaration")
        val NAMES = setOf(
            "identifier", "field_identifier", "type_identifier", "qualified_identifier", "destructor_name", "operator_name",
            "template_function", "primitive_type",
        )
        val FUNCTION_NAMES = setOf(
            "identifier", "field_identifier", "qualified_identifier", "destructor_name", "operator_name", "template_function",
        )
        val DECLARATOR_EXTRAS = setOf("type_qualifier", "attribute_specifier", "attribute_declaration", "ms_call_modifier", "ms_pointer_modifier")
        val OPERATOR_SPACE = Regex("operator\\s+(?=[^\\w\\s])")
    }
}
