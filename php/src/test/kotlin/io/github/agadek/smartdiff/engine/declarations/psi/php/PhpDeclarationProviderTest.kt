package io.github.agadek.smartdiff.engine.declarations.psi.php

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

class PhpDeclarationProviderTest : BasePlatformTestCase() {

    private fun provider(): DeclarationProvider =
        DeclarationProvider.EP_NAME.extensionList.single { it.supports("PHP") }

    private fun compare(old: String, new: String): ComparisonResult = ReadAction.compute(ThrowableComputable {
        val engine = DeclarationEngine(ProjectManager.getInstance().defaultProject)
        engine.compare(ComparisonRequest(oldText = old, newText = new, languageId = "PHP", fileName = "file.php"), Cancellation.NONE)
    })

    private fun tree(text: String): DeclarationTree = ReadAction.compute(ThrowableComputable {
        val project = ProjectManager.getInstance().defaultProject
        val provider = provider()
        provider.build(provider.parse(project, text, "file.php", null), Cancellation.NONE)
    })

    private fun changes(result: ComparisonResult): List<ChangeNode> = buildList {
        fun visit(node: ChangeNode) {
            add(node)
            node.children.forEach(::visit)
        }
        result.changes.forEach(::visit)
    }

    private fun name(node: ChangeNode): String = (node.subject as Subject.Declaration).name

    private fun changed(result: ComparisonResult): List<ChangeNode> = changes(result).filter { it.content == Content.CHANGED }

    /** Every declaration's range, own tokens and file region tokens are slices of [text] (details §9). */
    private fun assertOffsets(text: String, tree: DeclarationTree = tree(text)) {
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
    private fun assertOffsetsLfAndCrlf(text: String) {
        assertOffsets(text)
        val crlf = text.replace("\n", "\r\n")
        assertOffsets(crlf)
        assertEquals("same declarations on LF and CRLF", tree(text).all.map { it.kind to it.name }, tree(crlf).all.map { it.kind to it.name })
    }

    private fun slice(text: String, decl: Declaration): String = text.substring(decl.range.start, decl.range.end)

    fun `test R1 registers the PHP declaration provider when the plugin is present`() {
        // languages/19-php R1
        assertNotNull("the PHP plugin is loaded", PluginManagerCore.getPlugin(PluginId.getId("com.jetbrains.php")))
        val providers = DeclarationProvider.EP_NAME.extensionList.filter { it.supports("PHP") }
        assertEquals("one PHP provider, from smartdiff-php.xml", 1, providers.size)
        assertTrue(providers.single() is PhpDeclarationProvider)
        assertEquals(GuardedFeature.PHP_PROVIDER, providers.single().guardedFeature)
    }

    fun `test R2 supports php files when the plugin is present`() {
        // languages/19-php R2
        assertTrue(provider().supports("PHP"))
        assertFalse(provider().supports("php"))
        val result = compare("<?php\n\nfunction a() {}\n", "<?php\n\nfunction a() { echo 1; }\n")
        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.CHANGED, result.changes.single().content)
        assertOffsetsLfAndCrlf("<?php\n\nfunction a() {}\n")
    }

    fun `test R3 top-level declarations and kinds`() {
        // languages/19-php R3
        val text = """
            <?php

            declare(strict_types=1);

            use Foo\Bar;

            const VERSION = '1.0';

            function helper(): void {}

            class Point {}

            abstract class Shape {}

            interface Drawable {}

            trait Movable {}

            enum Suit {}

            define('LEGACY', 1);
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(
            listOf(
                DeclarationKind.CONSTANT to "VERSION",
                DeclarationKind.FUNCTION to "helper",
                DeclarationKind.CLASS to "Point",
                DeclarationKind.CLASS to "Shape",
                DeclarationKind.INTERFACE to "Drawable",
                DeclarationKind.TRAIT to "Movable",
                DeclarationKind.ENUM to "Suit",
                DeclarationKind.CONSTANT to "LEGACY",
            ),
            tree.topLevel.map { it.kind to it.name },
        )
        assertEquals("const VERSION = '1.0';", slice(text, tree.topLevel.first()))
        assertEquals("define('LEGACY', 1);", slice(text, tree.topLevel.last()))
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R4 a namespace contains the declarations after it or inside its braces`() {
        // languages/19-php R4
        val statement = """
            <?php

            namespace App\Model;

            use Countable;

            class Point {}

            function make(): Point { return new Point(); }

            namespace App\Other;

            const LIMIT = 3;
        """.trimIndent() + "\n"
        val tree = tree(statement)
        assertEquals(listOf(DeclarationKind.NAMESPACE to "App\\Model", DeclarationKind.NAMESPACE to "App\\Other"), tree.topLevel.map { it.kind to it.name })
        assertEquals(listOf("Point", "make"), tree.topLevel[0].children.map { it.name })
        assertEquals(listOf("LIMIT"), tree.topLevel[1].children.map { it.name })
        assertOffsetsLfAndCrlf(statement)

        val braced = """
            <?php
            namespace A\B {
                function a() {}
            }
            namespace {
                function b() {}
                class C {}
            }
        """.trimIndent() + "\n"
        val bracedTree = tree(braced)
        assertEquals(listOf("A\\B", "b", "C"), bracedTree.topLevel.map { it.name })
        assertEquals(listOf("a"), bracedTree.topLevel[0].children.map { it.name })
        val result = compare(braced, braced.replace("function a() {}", "function a() { return 1; }"))
        assertEquals("a", name(changed(result).single { it.children.isEmpty() }))
        assertOffsetsLfAndCrlf(braced)
    }

    fun `test R5 members of classes interfaces traits and enums`() {
        // languages/19-php R5
        val text = """
            <?php

            final class Point
            {
                use Movable;

                public const ORIGIN = 0;

                private int ${'$'}x = 0;

                public static ?string ${'$'}name = null;

                public function __construct(int ${'$'}x) { ${'$'}this->x = ${'$'}x; }

                public function move(): void {}
            }

            interface Shape
            {
                const SIDES = 0;

                public function area(): float;
            }

            trait Movable
            {
                protected int ${'$'}speed = 1;

                abstract public function move(): void;
            }

            enum Suit: string
            {
                case Hearts = 'H';
                case Spades = 'S';

                const Wild = self::Spades;

                public function label(): string { return ucfirst(${'$'}this->value); }
            }
        """.trimIndent() + "\n"
        val tree = tree(text)
        val point = tree.topLevel.single { it.name == "Point" }
        assertEquals(
            listOf(
                DeclarationKind.CONSTANT to "ORIGIN",
                DeclarationKind.PROPERTY to "x",
                DeclarationKind.PROPERTY to "name",
                DeclarationKind.CONSTRUCTOR to "__construct",
                DeclarationKind.METHOD to "move",
            ),
            point.children.map { it.kind to it.name },
        )
        assertEquals("private int \$x = 0;", slice(text, point.children[1]))
        assertEquals(listOf(DeclarationKind.CONSTANT to "SIDES", DeclarationKind.METHOD to "area"), tree.topLevel.single { it.name == "Shape" }.children.map { it.kind to it.name })
        assertEquals(listOf(DeclarationKind.PROPERTY to "speed", DeclarationKind.METHOD to "move"), tree.topLevel.single { it.name == "Movable" }.children.map { it.kind to it.name })
        assertEquals(
            listOf(
                DeclarationKind.ENUM_CONSTANT to "Hearts",
                DeclarationKind.ENUM_CONSTANT to "Spades",
                DeclarationKind.CONSTANT to "Wild",
                DeclarationKind.METHOD to "label",
            ),
            tree.topLevel.single { it.name == "Suit" }.children.map { it.kind to it.name },
        )
        val traitUse = compare(text, text.replace("    use Movable;\n", "    use Movable, Named;\n"))
        assertEquals("a trait use is the class's own code", "Point", name(changed(traitUse).single()))
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R6 each property or constant written together is its own declaration`() {
        // languages/19-php R6
        val text = """
            <?php

            const A = 1, B = 2;

            class Box
            {
                /** Both sides. */
                public int ${'$'}width = 0, ${'$'}height = 0;

                const MIN = 1, MAX = 9;
            }
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(listOf("A", "B", "Box"), tree.topLevel.map { it.name })
        assertEquals("const A = 1,", slice(text, tree.topLevel[0]))
        assertEquals("B = 2;", slice(text, tree.topLevel[1]))
        val box = tree.topLevel.last()
        assertEquals(listOf("width", "height", "MIN", "MAX"), box.children.map { it.name })
        assertEquals("/** Both sides. */\n    public int \$width = 0,", slice(text, box.children[0]))
        assertEquals("\$height = 0;", slice(text, box.children[1]))
        val inserted = compare(text, text.replace("\$width = 0, ", "\$width = 0, \$depth = 0, "))
        assertEquals("inserting a part changes no other part", listOf("depth" to Presence.ADDED), changes(inserted).filter { it.presence != Presence.MATCHED || it.content != Content.UNCHANGED }.filter { it.children.isEmpty() }.map { name(it) to it.presence })
        val result = compare(text, text.replace("\$height = 0", "\$height = 5"))
        assertEquals("height", name(changed(result).single { it.children.isEmpty() }))
        val renamed = compare(text, text.replace("MAX = 9", "TOP = 9"))
        assertEquals("MAX", (changes(renamed).single { name(it) == "TOP" }.renamedFrom as Subject.Declaration).name)
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R7 bodies closures anonymous classes imports and inline HTML are not declarations`() {
        // languages/19-php R7
        val text = """
            <html><body>
            <?php
            use App\Model\Point;
            use function App\helper;

            function render(): string
            {
                function inner() {}
                ${'$'}f = function () { return 1; };
                ${'$'}g = fn() => 2;
                ${'$'}o = new class { public function m() {} };
                const LOCAL = 1;
                return 'x';
            }

            if (!function_exists('polyfill')) {
                function polyfill() {}
            }
            ?>
            <p>text</p>
            </body></html>
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(listOf("render"), tree.topLevel.map { it.name })
        assertTrue("nothing inside render", tree.topLevel.single().children.isEmpty())
        val html = compare(text, text.replace("<p>text</p>", "<p>other</p>"))
        assertEquals(Status.ANALYZED, html.status)
        assertTrue("inline HTML belongs to the file region", changed(html).isEmpty())
        assertTrue("the change is still reported", html.hasReportedChanges)
        val body = compare(text, text.replace("public function m() {}", "public function m() { return 3; }"))
        assertEquals("render", name(changed(body).single()))
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R8 a declaration's range includes the comments and attributes directly above it`() {
        // languages/19-php R8
        val text = """
            <?php

            /** File doc. */

            /**
             * Point is a point.
             */
            #[Immutable]
            final class Point
            {
                // The x coordinate.
                # in pixels
                private int ${'$'}x = 0; // trailing

                /* Detached. */

                #[Pure]
                public function x(): int { return ${'$'}this->x; }
            }

            function a() {} // after a
            function b() {}
        """.trimIndent() + "\n"
        val tree = tree(text)
        val point = tree.topLevel.single { it.name == "Point" }
        assertTrue(slice(text, point), slice(text, point).startsWith("/**\n * Point is a point.\n */\n#[Immutable]\nfinal class Point"))
        assertEquals("// The x coordinate.\n    # in pixels\n    private int \$x = 0;", slice(text, point.children[0]))
        assertEquals("#[Pure]\n    public function x(): int { return \$this->x; }", slice(text, point.children[1]))
        assertEquals("function b() {}", slice(text, tree.topLevel.single { it.name == "b" }))

        val documented = compare(text, text.replace("function b() {}", "/** b does nothing. */\nfunction b() {}"))
        assertEquals("adding a doc comment changes the declaration", "b", name(documented.changes.single { it.content != Content.UNCHANGED }))
        val attribute = compare(text, text.replace("#[Pure]", "#[Pure, Deprecated]"))
        assertEquals("changing an attribute changes the method", "x", name(changed(attribute).single { it.children.isEmpty() }))
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R9 signatures are parameter types names and markers without defaults and results`() {
        // languages/19-php R9
        val text = """
            <?php

            function a(int ${'$'}x, ?Point ${'$'}p = null): void {}

            function b(${'$'}untyped, string ...${'$'}rest) {}

            function c(array &${'$'}items, int|string ${'$'}key, #[SensitiveParameter] A&B ${'$'}both) {}

            function d() {}

            class K
            {
                public function __construct(private readonly int ${'$'}y = 0, string ...${'$'}tags) {}
            }
        """.trimIndent() + "\n"
        val tree = tree(text)
        val functions = tree.topLevel.filter { it.kind == DeclarationKind.FUNCTION }
        assertEquals(listOf("int\$x,?Point\$p", "\$untyped,string...\$rest", "array&\$items,int|string\$key,A&B\$both", ""), functions.map { it.signature })
        assertEquals(listOf("int \$x, ?Point \$p", "\$untyped, string ...\$rest", "array &\$items, int|string \$key, A&B \$both", ""), functions.map { it.displaySignature })
        val constructor = tree.topLevel.last().children.single()
        assertEquals("int \$y, string ...\$tags", constructor.displaySignature)

        val renamedParameter = compare(text, text.replace("function a(int \$x", "function a(int \$z"))
        assertTrue("renaming a parameter changes the signature", changed(renamedParameter).single().signatureChanged)
        val defaults = compare(text, text.replace("?Point \$p = null", "?Point \$p = new Point()"))
        assertFalse("a default value is not the signature", changed(defaults).single().signatureChanged)
        val result = compare(text, text.replace("?Point \$p = null): void", "?Point \$p = null): int"))
        assertFalse("the return type is not the signature", changed(result).single().signatureChanged)
        val promoted = compare(text, text.replace("private readonly int \$y", "public int \$y"))
        assertEquals("a promoted property is part of the constructor", "__construct", name(changed(promoted).single { it.children.isEmpty() }))
        assertFalse(changed(promoted).single { it.children.isEmpty() }.signatureChanged)
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R10 whitespace outside strings is formatting-only`() {
        // languages/19-php R10
        val old = "<?php\n\nclass T\n{\n    public function f(int \$a,int \$b) { return g( \$a ); }\n}\n"
        val new = "<?php\n\nclass T {\n\tpublic function f(int \$a, int \$b)\n\t{\n\t\treturn g(\$a);\n\t}\n}\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        assertTrue(changes(result).filter { it.content != Content.UNCHANGED }.all { it.content == Content.FORMATTING_ONLY })
        assertTrue(changes(result).any { it.content == Content.FORMATTING_ONLY })
        assertEquals(WhitespacePolicy.INSIGNIFICANT, tree(old).whitespacePolicy)
    }

    fun `test R11 whitespace inside strings heredoc and nowdoc is content`() {
        // languages/19-php R11
        val text = """
            <?php

            function single() { return 'a b'; }

            function double() { return "a ${'$'}b c"; }

            function here()
            {
                return <<<EOT
                  Hello ${'$'}name
                  world
                EOT;
            }

            function now()
            {
                return <<<'EOT'
                  Hello
                  world
                EOT;
            }
        """.trimIndent() + "\n"
        for ((name, from, to) in listOf(
            Triple("single", "'a b'", "'a  b'"),
            Triple("double", "\"a \$b c\"", "\"a  \$b c\""),
            Triple("here", "      Hello \$name", "      Hello  \$name"),
            Triple("now", "      Hello\n      world\n    EOT;\n}\n", "      Hello\n        world\n    EOT;\n}\n"),
        )) {
            val result = compare(text, text.replace(from, to))
            val node = changes(result).single { it.content != Content.UNCHANGED }
            assertEquals(name, name(node))
            assertEquals("$name: whitespace inside the literal is a change", Content.CHANGED, node.content)
        }
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R12 extraction inlining renames kind changes and moves inside declarations work for PHP`() {
        // languages/19-php R12
        val before = """
            <?php

            class Cart
            {
                public function total(array ${'$'}items): int
                {
                    ${'$'}sum = 0;
                    foreach (${'$'}items as ${'$'}item) {
                        ${'$'}sum += ${'$'}item->price * ${'$'}item->quantity;
                    }
                    return ${'$'}sum;
                }
            }
        """.trimIndent() + "\n"
        val after = """
            <?php

            class Cart
            {
                public function total(array ${'$'}items): int
                {
                    return ${'$'}this->sumItems(${'$'}items);
                }

                private function sumItems(array ${'$'}items): int
                {
                    ${'$'}sum = 0;
                    foreach (${'$'}items as ${'$'}item) {
                        ${'$'}sum += ${'$'}item->price * ${'$'}item->quantity;
                    }
                    return ${'$'}sum;
                }
            }
        """.trimIndent() + "\n"
        assertTrue("extraction", compare(before, after).relationships.any { RelationshipKind.EXTRACT in it.kinds })
        assertTrue("inlining", compare(after, before).relationships.any { RelationshipKind.INLINE in it.kinds })
        val renamed = compare(before, before.replace("function total", "function sum"))
        assertTrue("rename", renamed.relationships.any { RelationshipKind.RENAME in it.kinds })
        val kind = compare("<?php\n\nclass Store\n{\n    public function get() {}\n}\n", "<?php\n\ntrait Store\n{\n    public function get() {}\n}\n")
        assertTrue("class to trait is a kind change", kind.relationships.any { RelationshipKind.KIND_CHANGE in it.kinds })
        assertEquals(DeclarationKind.CLASS, kind.changes.single().kindChangedFrom)
        val moved = compare(
            "<?php\n\nfunction f(\$o)\n{\n    if (\$o->valid) {\n        \$total = computeTotal(\$o->items, \$o->discount, \$o->tax);\n        ship(\$o, \$total);\n    }\n    \$label = describeOrder(\$o->customer, \$o->address, \$o->notes);\n    echo \$label;\n}\n",
            "<?php\n\nfunction f(\$o)\n{\n    \$label = describeOrder(\$o->customer, \$o->address, \$o->notes);\n    if (\$o->valid) {\n        \$total = computeTotal(\$o->items, \$o->discount, \$o->tax);\n        ship(\$o, \$total);\n    }\n    echo \$label;\n}\n",
        )
        assertEquals(Status.ANALYZED, moved.status)
        assertTrue("a statement moved before the if block: ${moved.changes}", moved.changes.single().codeMoves.isNotEmpty())
        val statements = tree("<?php\n\nfunction f()\n{\n    \$a = 1;\n    if (\$a > 0) {\n        c();\n    }\n}\n").topLevel.single().statements
        assertEquals("statements of the body and of the nested block", 3, statements.size)
        val namespaced = tree("<?php\n\nnamespace A;\n\nuse B;\n\necho 1;\n\nfunction f() {}\n").topLevel.single()
        assertTrue("a namespace's own statements are not code to move", namespaced.statements.isEmpty())
    }

    fun `test R13 merge outline and changes tree show PHP through the engine`() {
        // languages/19-php R13
        val base = "<?php\n\nfunction f(): int { return 1; }\n"
        val left = "<?php\n\nfunction f(): int { return 2; }\n"
        val right = "<?php\n\nfunction f(): int { return 3; }\n"
        val executor = Executors.newSingleThreadExecutor()
        try {
            val engine = DeclarationEngine(ProjectManager.getInstance().defaultProject)
            val joined = ReadAction.compute(ThrowableComputable {
                MergeComparison.compare(engine, MergeTexts(base, left, right, "PHP", "file.php"), Cancellation.NONE, executor)
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

    fun `test parse errors and empty files`() {
        // languages/19-php R2, details §9
        val added = compare("", "<?php\n\nfunction a() {}\n")
        assertEquals(Status.ANALYZED, added.status)
        assertEquals(Presence.ADDED, added.changes.single().presence)
        val broken = compare("<?php\n\nfunction a() {}\n\nfunction b() {}\n", "<?php\n\nfunction a() {}\n\nfunction b() { if ( }\n")
        assertEquals("a parse error in one function leaves the rest analyzed", Status.PARTIAL, broken.status)
        assertEquals("b", name(changes(broken).single { it.presence == Presence.MATCHED && it.content != Content.UNCHANGED }))
        val html = tree("<p>no php here</p>\n")
        assertTrue("a file without a PHP tag has no declarations", html.topLevel.isEmpty())
    }
}
