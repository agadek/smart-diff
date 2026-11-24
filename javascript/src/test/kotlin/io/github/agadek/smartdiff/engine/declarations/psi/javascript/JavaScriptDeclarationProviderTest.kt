package io.github.agadek.smartdiff.engine.declarations.psi.javascript

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
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

class JavaScriptDeclarationProviderTest : BasePlatformTestCase() {

    private fun provider(): DeclarationProvider =
        DeclarationProvider.EP_NAME.extensionList.single { it.supports("TypeScript") }

    private fun compare(old: String, new: String, fileName: String = "file.ts", languageId: String = "TypeScript"): ComparisonResult =
        ReadAction.compute(ThrowableComputable {
            DeclarationEngine(ProjectManager.getInstance().defaultProject)
                .compare(ComparisonRequest(old, new, languageId, fileName), Cancellation.NONE)
        })

    private fun tree(text: String, fileName: String = "file.ts", languageId: String = "TypeScript"): DeclarationTree =
        ReadAction.compute(ThrowableComputable {
            val provider = provider()
            val file = provider.parse(ProjectManager.getInstance().defaultProject, text, fileName, null, languageId)
            provider.build(file, Cancellation.NONE)
        }).also { assertOffsetsMatch(text, it) }

    private fun all(tree: DeclarationTree): List<Declaration> = buildList {
        fun visit(decl: Declaration) {
            add(decl)
            decl.children.forEach(::visit)
        }
        tree.topLevel.forEach(::visit)
    }

    /** §7: every declaration's range and tokens are the request text sliced by their offsets. */
    private fun assertOffsetsMatch(text: String, tree: DeclarationTree) {
        for (decl in all(tree)) {
            assertTrue("range of ${decl.name} inside the text", decl.range.start >= 0 && decl.range.end <= text.length && decl.range.start < decl.range.end)
            decl.ownTokens.zip(decl.ownTokenStarts).forEach { (token, start) ->
                assertEquals("token at $start in ${decl.name}", text.substring(start, start + token.text.length), token.text)
            }
        }
    }

    private fun Declaration.text(source: String) = source.substring(range.start, range.end)

    private fun shape(tree: DeclarationTree): List<String> = buildList {
        fun visit(decl: Declaration, depth: Int) {
            add("  ".repeat(depth) + "${decl.kind.label()} ${decl.name}" + (decl.displaySignature?.let { "($it)" } ?: ""))
            decl.children.forEach { visit(it, depth + 1) }
        }
        tree.topLevel.forEach { visit(it, 0) }
    }

    private fun nodes(result: ComparisonResult): List<ChangeNode> = buildList {
        fun visit(node: ChangeNode) {
            add(node)
            node.children.forEach(::visit)
        }
        result.changes.forEach(::visit)
    }

    private fun node(result: ComparisonResult, name: String): ChangeNode =
        nodes(result).firstOrNull { (it.subject as? Subject.Declaration)?.name == name } ?: error("no node $name in ${result.changes}")

    private fun relationship(result: ComparisonResult, kind: RelationshipKind) = result.relationships.any { kind in it.kinds }

    fun `test R1 registers the provider when the JavaScript plugin is present`() {
        // languages/16-javascript-typescript R1
        assertNotNull("the JavaScript plugin is loaded", PluginManagerCore.getPlugin(PluginId.getId("JavaScript")))
        val providers = DeclarationProvider.EP_NAME.extensionList.filter { it.supports("JavaScript") }
        assertEquals("one JavaScript provider", 1, providers.size)
        assertTrue(providers.single() is JavaScriptDeclarationProvider)
        val descriptor = javaClass.classLoader.getResource("META-INF/smartdiff-javascript.xml")?.readText().orEmpty()
        assertTrue("the optional descriptor registers the provider", descriptor.contains(JavaScriptDeclarationProvider::class.java.name))
        val pluginXml = javaClass.classLoader.getResources("META-INF/plugin.xml").toList().map { it.readText() }
            .first { it.contains("<id>io.github.agadek.smartdiff</id>") }
        assertTrue("the provider loads only with the JavaScript plugin", pluginXml.contains("""<depends optional="true" config-file="smartdiff-javascript.xml">JavaScript</depends>"""))
        assertEquals(GuardedFeatureName, providers.single().guardedFeature?.name)
    }

    fun `test R2 every JavaScript and TypeScript extension and language id is supported`() {
        // languages/16-javascript-typescript R2
        val provider = provider()
        for (id in listOf("JavaScript", "ECMAScript 6", "JSX Harmony", "Flow JS", "TypeScript", "TypeScript JSX")) {
            assertTrue("supports $id", provider.supports(id))
        }
        val expected = mapOf(
            "js" to "ECMAScript 6", "mjs" to "ECMAScript 6", "cjs" to "ECMAScript 6", "jsx" to "ECMAScript 6",
            "ts" to "TypeScript", "mts" to "TypeScript", "cts" to "TypeScript", "tsx" to "TypeScript JSX",
        )
        for ((extension, dialect) in expected) {
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension(extension)
            val languageId = (fileType as? LanguageFileType)?.language?.id
            assertTrue(".$extension is a JavaScript or TypeScript file type ($languageId)", languageId != null && provider.supports(languageId))
            val text = if (dialect == "ECMAScript 6") "function f(a) { return <b>a</b> }\n" else "function f(a: number): void {}\n"
            val result = compare(text, text.replace("f(", "g("), "file.$extension", languageId!!)
            assertEquals(".$extension analyzed", Status.ANALYZED, result.status)
            assertEquals(".$extension parsed as $dialect", dialect, JavaScriptInterop.dialectId("file.$extension", languageId))
        }
    }

    fun `test R3 functions classes and their members are declarations`() {
        // languages/16-javascript-typescript R3
        val text = """
            function f(a) { return a }
            class C {
              x = 1;
              #secret = 2;
              static count;
              constructor(a) { this.a = a }
              get value() { return 1 }
              set value(v) {}
              m(a, b) {}
              static s() {}
            }
        """.trimIndent() + "\n"
        val tree = tree(text, "file.js", "JavaScript")
        assertEquals(
            listOf(
                "function f(a)",
                "class C",
                "  field x", "  field #secret", "  field count",
                "  constructor constructor(a)",
                "  method get value()", "  method set value(v)",
                "  method m(a, b)", "  method s()",
            ),
            shape(tree),
        )
        val field = all(tree).single { it.name == "count" }
        assertEquals("static count;", field.text(text))
    }

    fun `test R4 variables are declarations and function-valued ones are functions`() {
        // languages/16-javascript-typescript R4
        val text = """
            const a = 1, b = (x: number) => x, c = function (y: string) {};
            let d;
            var e = async (p?: number) => { return p };
            namespace N { export const z = 1 }
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(
            listOf(
                "variable a", "function b(number)", "function c(string)", "variable d", "function e(number?)",
                "namespace N", "  variable z",
            ),
            shape(tree),
        )
        val (a, b, c) = tree.topLevel
        assertEquals("the first takes the keyword", "const a = 1", a.text(text))
        assertEquals("b = (x: number) => x", b.text(text))
        assertEquals("the last takes the semicolon", "c = function (y: string) {};", c.text(text))
    }

    fun `test R5 interfaces type aliases enums and namespaces are declarations`() {
        // languages/16-javascript-typescript R5
        val text = """
            interface Shape { area: number; scale(by: number): Shape; readonly name?: string }
            type Id = string | number;
            enum Color { Red, Green = 2 }
            const enum Flag { On }
            namespace A.B { export function f() {} }
            module M { }
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(
            listOf(
                "interface Shape", "  property area", "  method scale(number)", "  property name",
                "type alias Id",
                "enum Color", "  enum constant Red", "  enum constant Green",
                "enum Flag", "  enum constant On",
                "namespace A.B", "  function f()",
                "namespace M",
            ),
            shape(tree),
        )
        assertEquals("namespace", DeclarationKind.NAMESPACE.label())
        assertEquals("the new kind is last, so later kinds add after it", DeclarationKind.NAMESPACE, DeclarationKind.entries.last())
    }

    fun `test R6 declare module and declare global are modules with their items as members`() {
        // languages/16-javascript-typescript R6
        val text = """
            declare module "lib" {
              export function g(a: string): void;
              export interface Opts { x: number }
            }
            declare global {
              interface Window { app: string }
            }
        """.trimIndent() + "\n"
        val tree = tree(text, "types.d.ts")
        assertEquals(
            listOf(
                "module \"lib\"", "  function g(string)", "  interface Opts", "    property x",
                "module global", "  interface Window", "    property app",
            ),
            shape(tree),
        )
    }

    fun `test R7 export is in the declaration's range and an anonymous default export is named default`() {
        // languages/16-javascript-typescript R7
        val text = """
            export class A {}
            export function f() {}
            export const v = 1;
            export default function () {}
            export interface I {}
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(listOf("class A", "function f()", "variable v", "function default()", "interface I"), shape(tree))
        assertEquals("export class A {}", tree.topLevel[0].text(text))
        assertEquals("export const v = 1;", tree.topLevel[2].text(text))
        assertEquals("export default function () {}", tree.topLevel[3].text(text))
        val named = tree("export default class Widget { render() {} }\n")
        assertEquals(listOf("class Widget", "  method render()"), shape(named))
        assertEquals(listOf("class default"), shape(tree("export default class {}\n", "file.js", "JavaScript")))

        val result = compare("function f() {}\n", "export function f() {}\n")
        assertEquals("adding export changes the declaration", Content.CHANGED, node(result, "f").content)
    }

    fun `test R8 imports exports of expressions destructuring object literals and bodies are not declarations`() {
        // languages/16-javascript-typescript R8
        val text = """
            import { x } from "y";
            export { x };
            export * from "z";
            export default 42;
            const { a, b } = obj;
            const [c] = list;
            const o = { m() {}, k: 1 };
            module.exports = { p: 1 };
            exports.q = 2;
            function outer() { function inner() {} const local = 1; class Local {} }
            class K { static { init() } }
            interface I { [key: string]: unknown; (): void; new (): I }
        """.trimIndent() + "\n"
        val tree = tree(text, "file.ts")
        assertEquals(listOf("variable o", "function outer()", "class K", "interface I"), shape(tree))
    }

    fun `test R9 a declaration's range includes its decorators and JSDoc comment`() {
        // languages/16-javascript-typescript R9
        val text = """
            /** The widget. */
            @Component({ selector: "w" })
            export class Widget {
              /** Its size. */
              @Input() size = 1;
              /** Draws it. */
              @HostListener("click")
              draw() {}
            }
        """.trimIndent() + "\n"
        val tree = tree(text)
        val widget = tree.topLevel.single()
        assertTrue(widget.text(text).startsWith("/** The widget. */\n@Component"))
        assertEquals("/** Its size. */\n  @Input() size = 1;", widget.children[0].text(text))
        assertEquals("/** Draws it. */\n  @HostListener(\"click\")\n  draw() {}", widget.children[1].text(text))

        val old = text
        val new = text.replace("@Input() size", "@Input(\"s\") size")
        assertEquals("a changed decorator changes the field", Content.CHANGED, node(compare(old, new), "size").content)
        val doc = node(compare(old, text.replace("Draws it.", "Paints it.")), "draw")
        assertTrue("a changed JSDoc belongs to the method: ${doc.content}", doc.content != Content.UNCHANGED)
    }

    fun `test R10 TypeScript signatures are parameter types`() {
        // languages/16-javascript-typescript R10
        val text = """
            function f(this: Window, a: string, b?: number, ...rest: Array<string | number>) {}
            function g(a, b = 1, { c }: Opts, [d]) {}
            class P { constructor(private x: number, public readonly y: Map<string, number>) {} }
        """.trimIndent() + "\n"
        val tree = tree(text)
        val f = tree.topLevel[0]
        assertEquals("this: Window, string, number?, ...Array<string | number>", f.displaySignature)
        assertEquals("this:Window,string,number?,...Array<string|number>", f.signature)
        assertEquals("any, any, Opts, any", tree.topLevel[1].displaySignature)
        assertEquals("number, Map<string, number>", tree.topLevel[2].children.single().displaySignature)

        val result = compare("function f(a: string) {}\n", "function f(a: number) {}\n")
        assertTrue("a parameter type change is a signature change", node(result, "f").signatureChanged)
        val renamed = compare("function f(a: string) {}\n", "function f(b: string) {}\n")
        assertFalse("a parameter rename keeps the TypeScript signature", node(renamed, "f").signatureChanged)
    }

    fun `test R11 JavaScript signatures are parameter names`() {
        // languages/16-javascript-typescript R11
        val text = "function f(a, b = 2, { c, d = 1 }, [e], ...rest) {}\n"
        val f = tree(text, "file.js", "JavaScript").topLevel.single()
        assertEquals("a, b, {c, d}, [e], ...rest", f.displaySignature)
        assertEquals("a,b,{c,d},[e],...rest", f.signature)
        val result = compare("function f(a) {}\n", "function f(b) {}\n", "file.js", "JavaScript")
        assertTrue("a parameter rename changes the JavaScript signature", node(result, "f").signatureChanged)
        val defaults = compare("function f(a = 1) {}\n", "function f(a = 2) {}\n", "file.js", "JavaScript")
        assertFalse("a default value is not part of the signature", node(defaults, "f").signatureChanged)
    }

    fun `test R12 overload signatures and their implementation are separate declarations`() {
        // languages/16-javascript-typescript R12
        val text = """
            function parse(a: string): number;
            function parse(a: number): number;
            function parse(a: any) { return 1 }
            class C {
              m(a: string): void;
              m(a: number): void;
              m(a: any) {}
            }
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(
            listOf("function parse(string)", "function parse(number)", "function parse(any)", "class C", "  method m(string)", "  method m(number)", "  method m(any)"),
            shape(tree),
        )
        val result = compare(text, text.replace("function parse(a: number): number;\n", ""))
        val removed = nodes(result).filter { it.presence == Presence.REMOVED }
        assertEquals("one overload removed", listOf("number"), removed.map { (it.subject as Subject.Declaration).signature })
    }

    fun `test R13 whitespace and line breaks are formatting-only in a file with semicolons`() {
        // languages/16-javascript-typescript R13
        val old = """
            class C {
              m(a: number, b: string): void {
                const x = a + 1;
                if (x > 2) { call(x, b); }
                return;
              }
            }
        """.trimIndent() + "\n"
        val new = "class C { m(a: number,\n    b: string): void { const x = a+1; if (x>2) {\n call(x, b);\n }\n return; } }\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        for (n in nodes(result)) assertTrue("${n.subject} is formatting-only: ${n.content}", n.content == Content.FORMATTING_ONLY || n.content == Content.UNCHANGED)
        assertEquals(Content.FORMATTING_ONLY, node(result, "m").content)
        val string = compare("const s = \"a b\";\n", "const s = \"a  b\";\n")
        assertEquals("a string literal's spacing is content", Content.CHANGED, node(string, "s").content)
    }

    fun `test R14 a line break where a statement ends without a semicolon is a change`() {
        // languages/16-javascript-typescript R14
        val noSemicolons = "let x = a\nlet y = b\nx = y\n++z\n"
        val significant = ReadAction.compute(ThrowableComputable {
            JavaScriptInterop.significantWhitespace(provider().parse(project, noSemicolons, "file.js", null, "JavaScript"))
        }).map { noSemicolons.substring(it.start, it.end) to it.start }
        assertEquals("the line ends after statements without a semicolon", listOf("\n" to 9, "\n" to 19, "\n" to 25), significant)
        val withSemicolons = "let x = a;\nlet y = b;\n"
        assertTrue(ReadAction.compute(ThrowableComputable {
            JavaScriptInterop.significantWhitespace(provider().parse(project, withSemicolons, "file.js", null, "JavaScript"))
        }).isEmpty())
        val old = "function f() {\n  x = a\n  ++b\n  return x\n}\n"
        val joined = "function f() {\n  x = a ++b\n  return x\n}\n"
        assertFalse("joining lines without a semicolon is a change", node(compare(old, joined, "file.js", "JavaScript"), "f").content == Content.FORMATTING_ONLY)

        val call = "function f() {\n  const y = g\n  (1)\n}\n"
        val callJoined = "function f() {\n  const y = g (1)\n}\n"
        assertEquals("a line break inside one statement is formatting", Content.FORMATTING_ONLY, node(compare(call, callJoined, "file.js", "JavaScript"), "f").content)

        val ret = "function f() {\n  return value\n}\n"
        val split = "function f() {\n  return\n    value\n}\n"
        assertEquals("a line break after return is a change", Content.CHANGED, node(compare(ret, split, "file.js", "JavaScript"), "f").content)
        val yielded = "function* f() {\n  yield item\n}\n"
        val yieldSplit = "function* f() {\n  yield\n  item\n}\n"
        assertEquals("a line break after yield is a change", Content.CHANGED, node(compare(yielded, yieldSplit, "file.js", "JavaScript"), "f").content)
        val keywords = "function f() { for (;;) { if (a) break  outer; continue  x; } throw  e }\n"
        val afterKeywords = ReadAction.compute(ThrowableComputable {
            JavaScriptInterop.significantWhitespace(provider().parse(project, keywords, "file.js", null, "JavaScript"))
        }).map { keywords.substring(0, it.start).trimEnd().substringAfterLast(' ') }
        assertEquals("the space after break, continue and throw", listOf("break", "continue", "throw"), afterKeywords)
        val field = "class C {\n  x = 1\n  y = 2\n}\n"
        assertEquals("joining class fields is a change", Content.CHANGED, node(compare(field, "class C {\n  x = 1 y = 2\n}\n", "file.js", "JavaScript"), "y").content)

        val before = "function f() {\n  let x = a\n  g(x)\n}\n"
        val indented = "function f() {\n  let x = a\n  g(x)\n\n}\n"
        assertEquals("whitespace before a closing brace stays formatting-only", Content.FORMATTING_ONLY, node(compare(before, indented, "file.js", "JavaScript"), "f").content)
        val spaced = "function f() {\n  let x = a\n  g( x )\n}\n"
        assertEquals("whitespace elsewhere stays formatting-only", Content.FORMATTING_ONLY, node(compare(before, spaced, "file.js", "JavaScript"), "f").content)
    }

    fun `test R15 whitespace in JSX text is a change and inside a tag is not`() {
        // languages/16-javascript-typescript R15
        val old = "export const App = () => <div className=\"a\" id=\"b\">Hello world <b>x</b></div>;\n"
        val text = old.replace("Hello world", "Hello  world")
        assertEquals(Content.CHANGED, node(compare(old, text, "App.tsx", "TypeScript JSX"), "App").content)
        val tag = old.replace("className=\"a\" id", "className=\"a\"\n    id")
        assertEquals(Content.FORMATTING_ONLY, node(compare(old, tag, "App.tsx", "TypeScript JSX"), "App").content)
        val jsx = "const X = <p>a b</p>\n"
        assertEquals(Content.CHANGED, node(compare(jsx, "const X = <p>a\nb</p>\n", "X.jsx", "JavaScript"), "X").content)
    }

    fun `test R16 statements moved between methods are moved code`() {
        // languages/16-javascript-typescript R16
        val old = """
            class OrderService {
              process(orders: Order[]): void {
                for (const order of orders) {
                  validate(order);
                }
                const total = orders.length;
                if (total > 10) {
                  console.log("big batch " + total);
                  audit.record("batch", total);
                }
                save(orders);
              }

              finish(orders: Order[]): void {
                notify(orders);
              }
            }
        """.trimIndent() + "\n"
        val new = """
            class OrderService {
              process(orders: Order[]): void {
                for (const order of orders) {
                  validate(order);
                }
                save(orders);
              }

              finish(orders: Order[]): void {
                const total = orders.length;
                if (total > 10) {
                  console.log("big batch " + total);
                  audit.record("batch", total);
                }
                notify(orders);
              }
            }
        """.trimIndent() + "\n"
        val result = compare(old, new)
        val move = result.relationships.single { RelationshipKind.CODE_MOVE in it.kinds }
        assertEquals("process", (move.old.single().subject as Subject.Declaration).name)
        assertEquals("finish", (move.new.single().subject as Subject.Declaration).name)
    }

    fun `test R17 extraction inlining rename candidates and kind changes work`() {
        // languages/16-javascript-typescript R17
        val inline = """
            class Report {
              render(items: number[]): number {
                let sum = 0;
                for (const i of items) {
                  sum += i;
                }
                console.log("sum " + sum);
                return sum;
              }
            }
        """.trimIndent() + "\n"
        val extracted = """
            class Report {
              render(items: number[]): number {
                return this.total(items);
              }

              total(items: number[]): number {
                let sum = 0;
                for (const i of items) {
                  sum += i;
                }
                console.log("sum " + sum);
                return sum;
              }
            }
        """.trimIndent() + "\n"
        assertTrue("extraction", relationship(compare(inline, extracted), RelationshipKind.EXTRACT))
        assertTrue("inlining", relationship(compare(extracted, inline), RelationshipKind.INLINE))
        val renamed = extracted.replace("total", "sumOf")
        assertTrue("rename candidate", relationship(compare(extracted, renamed), RelationshipKind.RENAME))
        val kind = compare("class Shape { area: number }\n", "interface Shape { area: number }\n")
        assertTrue("class to interface is a kind change", relationship(kind, RelationshipKind.KIND_CHANGE))
    }

    fun `test R18 merge outline and changes tree show TypeScript files`() {
        // languages/16-javascript-typescript R18
        val base = "export class S {\n  f(): number { return 1 }\n}\n"
        val left = base.replace("return 1", "return 2")
        val right = base.replace("return 1", "return 3")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val engine = DeclarationEngine(ProjectManager.getInstance().defaultProject)
            val joined = ReadAction.compute(ThrowableComputable {
                MergeComparison.compare(engine, MergeTexts(base, left, right, "TypeScript", "file.ts"), Cancellation.NONE, executor)
            })
            assertTrue(joined.isAnalyzed(MergeSide.LEFT))
            assertTrue(joined.isAnalyzed(MergeSide.RIGHT))
            val model = MergeOutlineModel.build(
                joined, base, left, right, MergeSideName("main", "main"), MergeSideName("feature", "feature"), MergeOutlineLayout.GROUPED_BY_SIDE,
            )
            assertEquals("1 changed on both sides", model.summary)
            val summary = OutlineModel.summary(compare(base, left), oldText = base, newText = left)
            assertTrue("Changes tree summary: $summary", summary.contains("changed"))
        } finally {
            executor.shutdownNow()
        }
    }

    fun `test parse errors stay in the declaration that holds them`() {
        // languages/16-javascript-typescript-details §7
        val text = "function ok() {}\nfunction broken( {\n"
        val tree = tree(text)
        assertTrue(tree.topLevel.any { it.name == "ok" && !it.hasParseError })
        val result = compare("function ok() {}\n", text)
        assertTrue(result.status == Status.ANALYZED || result.status == Status.PARTIAL)
    }

    fun `test offsets hold on CRLF content`() {
        // languages/16-javascript-typescript-details §7
        val text = "export class A {\r\n  m(a: number) {\r\n    return a\r\n  }\r\n}\r\nconst b = 1, c = 2\r\n"
        val tree = tree(text)
        assertEquals(listOf("class A", "  method m(number)", "variable b", "variable c"), shape(tree))
    }

    private companion object {
        const val GuardedFeatureName = "JAVASCRIPT_PROVIDER"
    }
}
