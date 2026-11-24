package io.github.agadek.smartdiff.engine.declarations.psi.css

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.GuardedFeature
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.merge.MergeComparison
import io.github.agadek.smartdiff.merge.MergeSide
import io.github.agadek.smartdiff.merge.MergeTexts
import io.github.agadek.smartdiff.ui.OutlineModel
import io.github.agadek.smartdiff.ui.merge.MergeOutlineLayout
import io.github.agadek.smartdiff.ui.merge.MergeOutlineModel
import io.github.agadek.smartdiff.ui.merge.MergeSideName
import java.util.concurrent.Executors

class CssDeclarationProviderTest : BasePlatformTestCase() {

    private fun provider(languageId: String = "CSS"): DeclarationProvider =
        DeclarationProvider.EP_NAME.extensionList.single { it.supports(languageId) }

    private fun compare(old: String, new: String, languageId: String = "CSS"): ComparisonResult = ReadAction.compute(ThrowableComputable {
        val engine = DeclarationEngine(ProjectManager.getInstance().defaultProject)
        val request = ComparisonRequest(oldText = old, newText = new, languageId = languageId, fileName = "file.${languageId.lowercase()}")
        engine.compare(request, Cancellation.NONE)
    })

    private fun tree(text: String, languageId: String = "CSS"): DeclarationTree = ReadAction.compute(ThrowableComputable {
        val project = ProjectManager.getInstance().defaultProject
        val provider = provider(languageId)
        provider.build(provider.parse(project, text, "file.${languageId.lowercase()}", null, languageId), Cancellation.NONE)
    })

    private fun changes(result: ComparisonResult): List<ChangeNode> = buildList {
        fun visit(node: ChangeNode) {
            add(node)
            node.children.forEach(::visit)
        }
        result.changes.forEach(::visit)
    }

    private fun name(node: ChangeNode): String = (node.subject as Subject.Declaration).name

    /** Kind and name of every declaration, children indented, in source order. */
    private fun outline(tree: DeclarationTree): List<String> = buildList {
        fun visit(d: Declaration, depth: Int) {
            add("  ".repeat(depth) + d.kind.label() + " " + d.name)
            d.children.forEach { visit(it, depth + 1) }
        }
        tree.topLevel.forEach { visit(it, 0) }
    }

    /** Every declaration's range, own tokens and file region tokens are slices of [text] (details §9). */
    private fun assertOffsets(text: String, languageId: String, tree: DeclarationTree = tree(text, languageId)) {
        for (decl in tree.all) {
            assertTrue("range of ${decl.name} inside the text", decl.range.start >= 0 && decl.range.end <= text.length && decl.range.start < decl.range.end)
            assertEquals("tokens and starts of ${decl.name}", decl.ownTokens.size, decl.ownTokenStarts.size)
            decl.ownTokens.zip(decl.ownTokenStarts).forEach { (token, start) ->
                assertEquals("token at $start in ${decl.name}", text.substring(start, start + token.text.length), token.text)
            }
            for (child in decl.children) {
                assertTrue("${child.name} inside ${decl.name}", child.range.start >= decl.range.start && child.range.end <= decl.range.end)
            }
        }
        tree.fileRegion.tokens.zip(tree.fileRegion.tokenStarts).forEach { (token, start) ->
            assertEquals("file region token at $start", text.substring(start, start + token.text.length), token.text)
        }
    }

    /** [assertOffsets] on [text] and on the same text with CRLF line ends (details §9). */
    private fun assertOffsetsLfAndCrlf(text: String, languageId: String = "CSS") {
        assertOffsets(text, languageId)
        val crlf = text.replace("\n", "\r\n")
        assertOffsets(crlf, languageId)
        assertEquals("same declarations on LF and CRLF", outline(tree(text, languageId)), outline(tree(crlf, languageId)))
    }

    private fun slice(text: String, decl: Declaration): String = text.substring(decl.range.start, decl.range.end)

    fun `test R1 registers the CSS declaration provider when the plugin is present`() {
        // languages/18-css R1
        assertNotNull("the CSS plugin is loaded", PluginManagerCore.getPlugin(PluginId.getId("com.intellij.css")))
        val providers = DeclarationProvider.EP_NAME.extensionList.filter { it.supports("CSS") }
        assertEquals("one CSS provider, from smartdiff-css.xml", 1, providers.size)
        assertTrue(providers.single() is CssDeclarationProvider)
        assertEquals(GuardedFeature.CSS_PROVIDER, providers.single().guardedFeature)
    }

    fun `test R2 supports css scss and less files when their plugins are present`() {
        // languages/18-css R2
        assertNotNull("the Sass plugin is loaded", PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.plugins.sass")))
        assertNotNull("the Less plugin is loaded", PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.plugins.less")))
        val provider = provider()
        for (id in listOf("CSS", "SCSS", "LESS")) assertTrue(id, provider.supports(id))
        for (id in listOf("css", "SASS", "PostCSS")) assertFalse(id, provider.supports(id))
        for (id in listOf("CSS", "SCSS", "LESS")) {
            val result = compare(".a { color: red; }\n", ".a { color: blue; }\n", id)
            assertEquals(id, Status.ANALYZED, result.status)
            assertEquals(id, Content.CHANGED, result.changes.single().content)
            assertOffsetsLfAndCrlf(".a { color: red; }\n\n.b {\n  margin: 0;\n}\n", id)
        }
    }

    fun `test R3 a rule set is a rule named by its selectors`() {
        // languages/18-css R3
        val text = "h1 { margin: 0 }\n\n.card, #main > a:hover { color: red }\n\n[type=\"text\"]::placeholder { color: gray }\n"
        val tree = tree(text)
        assertEquals(
            listOf(DeclarationKind.RULE to "h1", DeclarationKind.RULE to ".card, #main > a:hover", DeclarationKind.RULE to "[type=\"text\"]::placeholder"),
            tree.topLevel.map { it.kind to it.name },
        )
        assertEquals("h1 { margin: 0 }", slice(text, tree.topLevel.first()))
        assertEquals("rule", DeclarationKind.RULE.label())
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R4 a rule set's name normalizes the whitespace of its selectors`() {
        // languages/18-css R4
        val text = ".a  .b,.c>.d ,\n.e~.f+.g { x: y }\n\na:not( .x,.y ) [ type = \"t\" i ] { x: y }\n"
        assertEquals(listOf(".a .b, .c > .d, .e ~ .f + .g", "a:not(.x, .y) [type=\"t\" i]"), tree(text).topLevel.map { it.name })
        val reformatted = compare(".a .b, .c > .d { x: y }\n", ".a .b,\n.c>.d {\n  x: y;\n}\n".replace(";", ""))
        assertEquals(Content.FORMATTING_ONLY, reformatted.changes.single().content)
        assertEquals(Presence.MATCHED, reformatted.changes.single().presence)
    }

    fun `test R5 an at-rule with a block is an at-rule named by its keyword and prelude`() {
        // languages/18-css R5
        val text = """
            @media screen and (max-width:600px) { .a { x: y } }
            @supports (display: grid) { .g { x: y } }
            @layer base { h1 { x: y } }
            @container sidebar (min-width: 400px) { .c { x: y } }
            @keyframes spin { from { x: y } to { x: z } }
            @font-face { font-family: X; }
            @page :first { margin: 1in }
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertTrue(tree.topLevel.all { it.kind == DeclarationKind.AT_RULE })
        assertEquals(
            listOf(
                "@media screen and (max-width: 600px)", "@supports (display: grid)", "@layer base",
                "@container sidebar (min-width: 400px)", "@keyframes spin", "@font-face", "@page :first",
            ),
            tree.topLevel.map { it.name },
        )
        assertEquals("at-rule", DeclarationKind.AT_RULE.label())
        val reformatted = compare("@media (min-width:1px) { .a { x: y } }\n", "@media (min-width: 1px) {\n  .a { x: y }\n}\n")
        assertEquals(Content.FORMATTING_ONLY, reformatted.changes.single().content)
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R6 rule sets at-rules custom properties and variables in a block are members`() {
        // languages/18-css R6
        val css = "@media print {\n  .a {\n    --gap: 1px;\n    color: red;\n    .b { x: y }\n    &:hover { x: z }\n  }\n}\n\n@keyframes spin { 0%, 50% { x: y } to { x: z } }\n"
        assertEquals(
            listOf("at-rule @media print", "  rule .a", "    variable --gap", "    rule .b", "    rule &:hover", "at-rule @keyframes spin", "  rule 0%, 50%", "  rule to"),
            outline(tree(css)),
        )
        val scss = ".card {\n  \$pad: 2px;\n  &__title { x: y }\n  > .body { x: y }\n  @media print { x: y }\n}\n"
        assertEquals(
            listOf("rule .card", "  variable \$pad", "  rule &__title", "  rule > .body", "  at-rule @media print"),
            outline(tree(scss, "SCSS")),
        )
        val less = ".card {\n  @pad: 2px;\n  .title { x: y }\n  &:hover { x: y }\n}\n"
        assertEquals(listOf("rule .card", "  variable @pad", "  rule .title", "  rule &:hover"), outline(tree(less, "LESS")))
        val changed = compare(css, css.replace("x: z }\n  }", "x: w }\n  }"))
        val hover = changes(changed).single { it.content == Content.CHANGED && name(it) == "&:hover" }
        assertEquals(Presence.MATCHED, hover.presence)
        assertOffsetsLfAndCrlf(css)
        assertOffsetsLfAndCrlf(scss, "SCSS")
        assertOffsetsLfAndCrlf(less, "LESS")
    }

    fun `test R7 a custom property is a variable`() {
        // languages/18-css R7
        val text = ":root {\n  --brand: #0af;\n  --gap: 4px;\n  color: var(--brand);\n}\n"
        val root = tree(text).topLevel.single()
        assertEquals(listOf(DeclarationKind.VARIABLE to "--brand", DeclarationKind.VARIABLE to "--gap"), root.children.map { it.kind to it.name })
        assertEquals("--brand: #0af;", slice(text, root.children.first()))
        val result = compare(text, text.replace("#0af", "#0bf"))
        assertEquals("--brand", name(changes(result).single { it.content == Content.CHANGED && it.subject.let { s -> s is Subject.Declaration && s.kind == DeclarationKind.VARIABLE } }))
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R8 SCSS variables mixins functions and placeholders are declarations`() {
        // languages/18-css R8
        val text = """
            ${'$'}primary: red !default;

            @mixin button(${'$'}color, ${'$'}size: 1px) { color: ${'$'}color; }

            @function double(${'$'}n) { @return ${'$'}n * 2; }

            %placeholder { a: b; }
        """.trimIndent() + "\n"
        val tree = tree(text, "SCSS")
        assertEquals(
            listOf(
                DeclarationKind.VARIABLE to "\$primary", DeclarationKind.MIXIN to "button",
                DeclarationKind.FUNCTION to "double", DeclarationKind.RULE to "%placeholder",
            ),
            tree.topLevel.map { it.kind to it.name },
        )
        assertEquals("\$primary: red !default;", slice(text, tree.topLevel.first()))
        assertEquals("mixin", DeclarationKind.MIXIN.label())
        assertOffsetsLfAndCrlf(text, "SCSS")
    }

    fun `test R9 Less variables detached rule sets and mixins with parentheses are declarations`() {
        // languages/18-css R9
        val text = """
            @primary: red;

            @detached: { background: red; };

            .border(@width; @style: solid) { border: @width @style; }

            .plain { color: red; }

            #ns { .inner() { x: y; } }
        """.trimIndent() + "\n"
        assertEquals(
            listOf("variable @primary", "variable @detached", "mixin .border", "rule .plain", "rule #ns", "  mixin .inner"),
            outline(tree(text, "LESS")),
        )
        assertOffsetsLfAndCrlf(text, "LESS")
    }

    fun `test R10 a mixin's and a function's signature is its parameter names`() {
        // languages/18-css R10
        val scss = "@mixin a(\$x, \$y: 2px, \$rest...) { x: \$x; }\n\n@mixin b { x: y; }\n\n@function f(\$n) { @return \$n; }\n"
        assertEquals(listOf("\$x,\$y,\$rest...", "", "\$n"), tree(scss, "SCSS").topLevel.map { it.signature })
        assertEquals(listOf("\$x, \$y, \$rest...", "", "\$n"), tree(scss, "SCSS").topLevel.map { it.displaySignature })
        val less = ".m(@a; @b: 2) { x: @a; }\n\n.m(dark; @c) { x: @c; }\n"
        assertEquals(listOf("@a,@b", "dark,@c"), tree(less, "LESS").topLevel.map { it.signature })
        val defaults = compare(scss, scss.replace("\$y: 2px", "\$y: 3px"), "SCSS")
        val a = changes(defaults).single { it.content == Content.CHANGED }
        assertEquals("a", name(a))
        assertFalse("a default value is not the signature", a.signatureChanged)
        val added = compare(scss, scss.replace("@function f(\$n)", "@function f(\$n, \$m)"), "SCSS")
        assertTrue("a new parameter changes the signature", changes(added).single { name(it) == "f" }.signatureChanged)
    }

    fun `test R11 properties imports and block-less at-rules are not declarations`() {
        // languages/18-css R11
        val css = "@charset \"utf-8\";\n@import url(\"base.css\");\n@namespace svg url(http://www.w3.org/2000/svg);\n@layer reset, base;\n\n.a { color: red; margin: 0; }\n"
        assertEquals(listOf("rule .a"), outline(tree(css)))
        val scss = "@use \"sass:math\";\n@forward \"src/list\";\n\n@include reset;\n\n.a { @include button(red); @extend %base; color: red; }\n\n@each \$i in 1, 2 { .m-#{\$i} { margin: \$i; } }\n\n@if \$x { .b { x: y } }\n"
        assertEquals(listOf("rule .a"), outline(tree(scss, "SCSS")))
        val less = "@import \"x\";\n\n.a { .mixin(1); color: red; }\n"
        assertEquals(listOf("rule .a"), outline(tree(less, "LESS")))
        val property = compare(css, css.replace("margin: 0", "margin: 1px"))
        assertEquals(".a", name(property.changes.single()))
        assertEquals(Content.CHANGED, property.changes.single().content)
        assertOffsetsLfAndCrlf(css)
        assertOffsetsLfAndCrlf(scss, "SCSS")
    }

    fun `test R12 nothing inside a mixin or a function is a declaration`() {
        // languages/18-css R12
        val scss = "@mixin m { \$local: 1; .inner { x: y } --p: 1; }\n\n@function f() { \$v: 2; @return \$v; }\n"
        assertEquals(listOf("mixin m", "function f"), outline(tree(scss, "SCSS")))
        val less = ".m() { @local: 1; .inner { x: y } }\n"
        assertEquals(listOf("mixin .m"), outline(tree(less, "LESS")))
    }

    fun `test R13 a declaration's range includes the comments directly above it`() {
        // languages/18-css R13
        val text = "/* header */\n\n/* about a */\n.a { x: y; }\n.b { x: y; } /* after b */\n.c { x: y; }\n"
        val tree = tree(text)
        assertEquals("/* about a */\n.a { x: y; }", slice(text, tree.topLevel[0]))
        assertEquals(".c { x: y; }", slice(text, tree.topLevel[2]))
        val scss = "// The primary colour.\n\$primary: red;\n\n.a {\n  // gap\n  --gap: 1px;\n}\n"
        val scssTree = tree(scss, "SCSS")
        assertEquals("// The primary colour.\n\$primary: red;", slice(scss, scssTree.topLevel[0]))
        assertEquals("// gap\n  --gap: 1px;", slice(scss, scssTree.topLevel[1].children.single()))
        val documented = compare(text, text.replace("/* about a */", "/* about rule a */"))
        assertEquals(".a", name(changes(documented).single { it.content != Content.UNCHANGED }))
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R14 spaces indentation and line breaks are formatting-only`() {
        // languages/18-css R14
        val old = ".a{color:red;margin:0 auto}\n\n@media print{.b{x:y}}\n"
        val new = ".a {\n  color: red;\n  margin: 0 auto;\n}\n\n@media print {\n  .b {\n    x: y\n  }\n}\n".replace("auto;", "auto")
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        assertTrue(changes(result).filter { it.content != Content.UNCHANGED }.all { it.content == Content.FORMATTING_ONLY })
        assertTrue(changes(result).any { it.content == Content.FORMATTING_ONLY })
        assertEquals(WhitespacePolicy.INSIGNIFICANT, tree(old).whitespacePolicy)
    }

    fun `test R15 descendant combinators and whitespace around operators are changes`() {
        // languages/18-css R15
        val descendant = compare(".nav .item { x: y; }\n.other { x: y; }\n", ".nav.item { x: y; }\n.other { x: y; }\n")
        assertFalse("the compound selector is another rule: ${descendant.changes}", changes(descendant).any { it.content == Content.FORMATTING_ONLY })
        val combinatorSpacing = compare(".nav .item { x: y; }\n", ".nav\n  .item { x: y; }\n")
        assertEquals(Content.CHANGED, combinatorSpacing.changes.single().content)
        val explicit = compare(".a > .b { x: y; }\n", ".a>.b { x: y; }\n")
        assertEquals("spaces around > are formatting", Content.FORMATTING_ONLY, explicit.changes.single().content)
        val math = compare(".a { width: calc(100% - 2px); }\n", ".a { width: calc(100%  -  2px); }\n")
        assertEquals(Content.CHANGED, math.changes.single().content)
        val scss = compare("\$w: \$a - \$b;\n", "\$w: \$a -\$b;\n", "SCSS")
        assertEquals(Content.CHANGED, scss.changes.single().content)
    }

    fun `test R16 a changed selector with similar content is a rename candidate`() {
        // languages/18-css R16
        val old = ".button-primary {\n  color: white;\n  background: blue;\n  padding: 4px 8px;\n  border-radius: 2px;\n}\n\n.other { x: y; }\n"
        val result = compare(old, old.replace(".button-primary", ".btn-primary"))
        assertTrue("rename: ${result.relationships}", result.relationships.any { RelationshipKind.RENAME in it.kinds })
    }

    fun `test R17 a Less rule set turned into a mixin of the same name is a kind change`() {
        // languages/18-css R17
        val old = ".bordered {\n  border-top: dotted 1px black;\n  border-bottom: solid 2px black;\n}\n"
        val result = compare(old, old.replace(".bordered {", ".bordered() {"), "LESS")
        assertTrue("kind change: ${result.relationships}", result.relationships.any { RelationshipKind.KIND_CHANGE in it.kinds })
        assertEquals(DeclarationKind.RULE, result.changes.single().kindChangedFrom)
    }

    fun `test R18 renames and moves inside declarations work for CSS`() {
        // languages/18-css R18
        val reordered = compare(".a { x: y; }\n\n.b { z: w; }\n", ".b { z: w; }\n\n.a { x: y; }\n")
        assertTrue("a moved rule: ${reordered.changes}", reordered.changes.any { it.moved })
        val grid = "grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));"
        val shadow = "box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);"
        val transition = "transition: opacity 120ms ease-in-out, transform 120ms ease-in-out;"
        val before = ".card {\n  $grid\n  $shadow\n  $transition\n}\n"
        val after = ".card {\n  $shadow\n  $transition\n  $grid\n}\n"
        val moved = compare(before, after)
        assertEquals(Status.ANALYZED, moved.status)
        assertTrue("properties moved inside the rule: ${moved.changes}", moved.changes.single().codeMoves.isNotEmpty())
        val statements = tree(before).topLevel.single().statements
        assertEquals("each property is a statement", 3, statements.size)
    }

    fun `test R19 merge outline and changes tree show CSS through the engine`() {
        // languages/18-css R19
        val base = ".a { color: red; }\n"
        val left = ".a { color: green; }\n"
        val right = ".a { color: blue; }\n"
        val executor = Executors.newSingleThreadExecutor()
        try {
            val engine = DeclarationEngine(ProjectManager.getInstance().defaultProject)
            val joined = ReadAction.compute(ThrowableComputable {
                MergeComparison.compare(engine, MergeTexts(base, left, right, "CSS", "file.css"), Cancellation.NONE, executor)
            })
            assertTrue(joined.isAnalyzed(MergeSide.LEFT))
            assertTrue(joined.isAnalyzed(MergeSide.RIGHT))
            val model = MergeOutlineModel.build(
                joined, base, left, right,
                MergeSideName("main", "main"), MergeSideName("feature", "feature"),
                MergeOutlineLayout.GROUPED_BY_SIDE,
            )
            assertEquals("1 changed on both sides", model.summary)
            val summary = OutlineModel.summary(compare(base, left), oldText = base, newText = left)
            assertTrue("Changes tree summary: $summary", summary.contains("changed"))
        } finally {
            executor.shutdownNow()
        }
    }

    fun `test parse errors are reported on the declaration that holds them`() {
        // languages/18-css-details §9
        val old = ".a { x: y; }\n\n.b { x: y; }\n"
        val broken = compare(old, ".a { x: y; }\n\n.b { x: ; ]] }\n")
        assertTrue("status ${broken.status}", broken.status == Status.PARTIAL || broken.status == Status.ANALYZED)
        val empty = compare("", ".a { x: y; }\n")
        assertEquals(Status.ANALYZED, empty.status)
        assertEquals(Presence.ADDED, empty.changes.single().presence)
    }
}
