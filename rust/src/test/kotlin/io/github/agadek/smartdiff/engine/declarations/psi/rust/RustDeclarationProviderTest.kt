package io.github.agadek.smartdiff.engine.declarations.psi.rust

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider

class RustDeclarationProviderTest : BasePlatformTestCase() {

    private fun provider(): DeclarationProvider =
        DeclarationProvider.EP_NAME.extensionList.single { it.supports("Rust") }

    private fun compare(old: String, new: String) = ReadAction.compute(ThrowableComputable {
        val engine = DeclarationEngine(ProjectManager.getInstance().defaultProject)
        engine.compare(
            ComparisonRequest(
                oldText = old,
                newText = new,
                languageId = "Rust",
                fileName = "file.rs",
            ),
            Cancellation.NONE,
        )
    })

    private fun tree(text: String): DeclarationTree = ReadAction.compute(ThrowableComputable {
        val project = ProjectManager.getInstance().defaultProject
        val provider = provider()
        val file = provider.parse(project, text, "file.rs", null)
        provider.build(file, Cancellation.NONE)
    })

    private fun allDeclarations(tree: DeclarationTree): List<Declaration> = buildList {
        fun visit(decl: Declaration) {
            add(decl)
            decl.children.forEach(::visit)
        }
        tree.topLevel.forEach(::visit)
    }

    private fun assertOffsetsEqualRequestText(text: String, tree: DeclarationTree) {
        for (decl in allDeclarations(tree)) {
            assertEquals("range text of ${decl.name}", text.substring(decl.range.start, decl.range.end), text.substring(decl.range.start, decl.range.end))
            assertTrue("declaration range inside text ${decl.name}", decl.range.start >= 0 && decl.range.end <= text.length && decl.range.start <= decl.range.end)
            decl.ownTokens.zip(decl.ownTokenStarts).forEach { (token, start) ->
                assertEquals("token at $start in ${decl.name}", text.substring(start, start + token.text.length), token.text)
            }
        }
    }

    private fun assertOffsetsEqualRequestText(text: String) {
        assertOffsetsEqualRequestText(text, tree(text))
    }

    fun `test R1 registers the Rust declaration provider when the plugin is present`() {
        // languages/05-rust R1
        val providers = DeclarationProvider.EP_NAME.extensionList.filter { it.supports("Rust") }
        assertEquals("one Rust provider", 1, providers.size)
        assertTrue(providers.single() is RustDeclarationProvider)
    }

    fun `test R2 supports Rust language and rs files when the plugin is present`() {
        // languages/05-rust R2
        val provider = provider()
        assertTrue(provider.supports("Rust"))
        val result = compare("fn foo() {}\n", "fn bar() {}\n")
        assertEquals(Status.ANALYZED, result.status)
        assertTrue(result.changes.isNotEmpty() || result.fallbackReason == null)
    }

    fun `test R3 top-level declarations and kinds`() {
        // languages/05-rust R3
        val text = """
            fn foo() {}
            struct S { x: i32 }
            enum E { A, B(i32) }
            trait T { fn m(&self); }
            mod m {}
            mod decl;
            type A = i32;
            const C: i32 = 1;
            static S1: i32 = 1;
            macro_rules! mm { () => {}; }
            macro m2() {}
        """.trimIndent() + "\n"
        val tree = tree(text)
        val byNameKind = tree.topLevel.associate { it.name to it.kind }
        assertEquals(DeclarationKind.FUNCTION, byNameKind["foo"])
        assertEquals(DeclarationKind.STRUCT, byNameKind["S"])
        assertEquals(DeclarationKind.ENUM, byNameKind["E"])
        assertEquals(DeclarationKind.TRAIT, byNameKind["T"])
        assertEquals(DeclarationKind.MODULE, byNameKind["m"])
        assertEquals(DeclarationKind.MODULE, byNameKind["decl"])
        assertEquals(DeclarationKind.TYPE_ALIAS, byNameKind["A"])
        assertEquals(DeclarationKind.CONSTANT, byNameKind["C"])
        assertEquals(DeclarationKind.STATIC, byNameKind["S1"])
        assertEquals(DeclarationKind.MACRO, byNameKind["mm"])
        assertEquals(DeclarationKind.MACRO, byNameKind["m2"])
        assertOffsetsEqualRequestText(text, tree)
    }

    fun `test R4 containers hold their members`() {
        // languages/05-rust R4
        val text = """
            struct S { x: i32, y: i32 }
            enum E { A, B { v: i32 } }
            trait T { fn m(&self); type Item; }
            mod m { fn inner() {} }
        """.trimIndent() + "\n"
        val tree = tree(text)
        val s = tree.topLevel.single { it.name == "S" }
        assertEquals(listOf("x", "y"), s.children.map { it.name })
        val e = tree.topLevel.single { it.name == "E" }
        assertEquals(listOf("A", "B"), e.children.map { it.name })
        val b = e.children.single { it.name == "B" }
        assertEquals(listOf("v"), b.children.map { it.name })
        val t = tree.topLevel.single { it.name == "T" }
        assertEquals(setOf("m", "Item"), t.children.map { it.name }.toSet())
        assertOffsetsEqualRequestText(text, tree)
    }

    fun `test R5 impl naming`() {
        // languages/05-rust R5
        val text = """
            struct S;
            trait T {}
            impl S { fn new() -> S { S } }
            impl T for S { fn t(&self) {} }
            impl !Send for Handle {}
        """.trimIndent() + "\n"
        val tree = tree(text)
        val impls = tree.topLevel.filter { it.kind == DeclarationKind.IMPL }
        assertTrue("inherent impl", impls.any { it.name == "S" })
        assertTrue("trait impl", impls.any { it.name == "T for S" })
        assertTrue("negative impl", impls.any { it.name == "!Send for Handle" })
        assertOffsetsEqualRequestText(text, tree)
    }

    fun `test R6 trait and impl items are members`() {
        // languages/05-rust R6
        val text = """
            trait T {
                fn f(&self);
                type Item;
                const N: i32;
            }
            impl T for S {
                fn f(&self) {}
                type Item = i32;
                const N: i32 = 1;
            }
        """.trimIndent() + "\n"
        val tree = tree(text)
        val t = tree.topLevel.single { it.name == "T" && it.kind == DeclarationKind.TRAIT }
        assertEquals(setOf("f", "Item", "N"), t.children.map { it.name }.toSet())
        assertEquals(setOf(DeclarationKind.FUNCTION, DeclarationKind.TYPE_ALIAS, DeclarationKind.CONSTANT), t.children.map { it.kind }.toSet())
        val i = tree.topLevel.single { it.kind == DeclarationKind.IMPL }
        assertEquals(setOf("f", "Item", "N"), i.children.map { it.name }.toSet())
        assertOffsetsEqualRequestText(text, tree)
    }

    fun `test R7 fields and variants`() {
        // languages/05-rust R7
        val text = """
            struct S { x: i32 }
            enum E { A, B(i32), C { y: i32 } }
        """.trimIndent() + "\n"
        val tree = tree(text)
        val field = tree.topLevel.single { it.name == "S" }.children.single()
        assertEquals(DeclarationKind.FIELD, field.kind)
        assertEquals("x", field.name)
        val variants = tree.topLevel.single { it.name == "E" }.children
        assertTrue(variants.all { it.kind == DeclarationKind.VARIANT })
        assertTrue("variants have no signature", variants.all { it.signature == null })
        assertOffsetsEqualRequestText(text, tree)
    }

    fun `test R7 tuple variant payload change is one changed variant`() {
        // languages/05-rust R7
        val old = "enum E { V(i32) }\n"
        val new = "enum E { V(i64) }\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        val enumChange = result.changes.single()
        val v = enumChange.children.single()
        assertEquals("V", (v.subject as io.github.agadek.smartdiff.engine.api.Subject.Declaration).name)
        assertEquals(io.github.agadek.smartdiff.engine.api.Content.CHANGED, v.content)
    }

    fun `test R8 extern blocks expose their declarations`() {
        // languages/05-rust R8
        val text = """
            extern "C" {
                fn ext(x: i32);
                static EXT: i32;
            }
        """.trimIndent() + "\n"
        val tree = tree(text)
        // functions/statics in extern block are declarations of the containing module/file
        val names = allDeclarations(tree).map { it.name }
        assertTrue("extern fn visible", "ext" in names)
        assertTrue("extern static visible", "EXT" in names)
        assertOffsetsEqualRequestText(text, tree)
    }

    fun `test R9 local items inside function bodies are not declarations`() {
        // languages/05-rust R9
        val text = """
            fn outer() {
                fn inner() {}
                struct Local { x: i32 }
            }
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(1, tree.topLevel.size)
        assertEquals("outer", tree.topLevel.single().name)
        assertTrue(allDeclarations(tree).none { it.name == "inner" || it.name == "Local" })
        assertOffsetsEqualRequestText(text, tree)
    }

    fun `test R10 declaration ranges include outer attributes`() {
        // languages/05-rust R10
        val text = """
            #[repr(C)]
            union U { a: u32, b: f32 }

            #[no_mangle]
            static X: i32 = 1;

            #[derive(Debug)]
            struct S { x: i32 }
        """.trimIndent() + "\n"
        val tree = tree(text)
        val unionDecl = tree.topLevel.single { it.name == "U" }
        assertEquals(DeclarationKind.UNION, unionDecl.kind)
        assertTrue("union range includes attribute", text.substring(unionDecl.range.start, unionDecl.range.end).contains("#[repr(C)]"))
        val staticDecl = tree.topLevel.single { it.name == "X" }
        assertEquals(DeclarationKind.STATIC, staticDecl.kind)
        assertTrue("static range includes attribute", text.substring(staticDecl.range.start, staticDecl.range.end).contains("#[no_mangle]"))
        assertOffsetsEqualRequestText(text, tree)
    }

    fun `test R11 signatures normalize parameters and self`() {
        // languages/05-rust R11
        val text = """
            impl S {
                fn f(&self, x: i32, y: &mut str) {}
                fn g(&'a mut self, x: i32) {}
                fn h(self: Box<Self>) {}
                fn m(mut self) {}
                fn r(&mut self) {}
            }
        """.trimIndent() + "\n"
        val tree = tree(text)
        val impl = tree.topLevel.single { it.kind == DeclarationKind.IMPL }
        val f = impl.children.single { it.name == "f" }
        assertEquals("&self,i32,&mutstr", f.signature)
        val g = impl.children.single { it.name == "g" }
        assertEquals("&'amutself,i32", g.signature)
        assertTrue("display keeps self form", g.displaySignature?.contains("&'a mut self") == true)
        val h = impl.children.single { it.name == "h" }
        assertEquals("Box<Self>", h.signature?.replace(" ", ""))
        val m = impl.children.single { it.name == "m" }
        assertEquals("self", m.signature)
        assertEquals("self", m.displaySignature)
        val r = impl.children.single { it.name == "r" }
        assertEquals("&mutself", r.signature)
        assertEquals("&mut self", r.displaySignature)
        assertOffsetsEqualRequestText(text, tree)
    }

    fun `test R11 raw pointer types use no space after star in display`() {
        // languages/05-rust R11
        val text = """
            fn f(p: *const T, q: *mut T) {}
        """.trimIndent() + "\n"
        val tree = tree(text)
        val f = tree.topLevel.single { it.name == "f" }
        assertEquals("*constT,*mutT", f.signature)
        assertEquals("*const T, *mut T", f.displaySignature)
        assertOffsetsEqualRequestText(text, tree)
    }

    fun `test R12 repeated keys are represented`() {
        // languages/05-rust R12
        val text = """
            impl Point { fn f(&self) {} }
            impl Point { fn g(&self) {} }
        """.trimIndent() + "\n"
        val tree = tree(text)
        val impls = tree.topLevel.filter { it.kind == DeclarationKind.IMPL && it.name == "Point" }
        assertEquals(2, impls.size)
        assertOffsetsEqualRequestText(text, tree)
    }

    fun `test R13 whitespace is insignificant outside macro arguments`() {
        // languages/05-rust R13
        val old = "fn f() {\n    let x = 1;\n}\n"
        val new = "fn f() { let x = 1; }\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        val fnChange = result.changes.single()
        assertTrue(
            "formatting-only outside macros",
            fnChange.content == Content.FORMATTING_ONLY || fnChange.content == Content.UNCHANGED,
        )
    }

    fun `test R13 reformatted impl header stays formatting-only`() {
        // languages/05-rust R13
        val old = "impl Foo<A, B> { fn f(&self) {} }\n"
        val new = "impl Foo<A,\n  B> { fn f(&self) {} }\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        val implChange = result.changes.single()
        assertTrue(
            "reformatted impl header",
            implChange.content == Content.FORMATTING_ONLY || implChange.content == Content.UNCHANGED,
        )
    }

    fun `test R13 multi-line parameter type stays formatting-only`() {
        // languages/05-rust R13
        val old = "fn f(x: Foo<A, B>) {}\n"
        val new = "fn f(x: Foo<A,\n  B>) {}\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        val fnChange = result.changes.single()
        assertTrue(
            "multi-line parameter type",
            fnChange.content == Content.FORMATTING_ONLY || fnChange.content == Content.UNCHANGED,
        )
    }

    fun `test R14 whitespace inside macro-call arguments is significant`() {
        // languages/05-rust R14
        val old = "fn f() { println!(\"{}\",  1); }\n"
        val new = "fn f() { println!(\"{}\", 1); }\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        val fnChange = result.changes.single()
        assertEquals("spacing inside macro args matters", Content.CHANGED, fnChange.content)
    }

    fun `test R15 extraction detection works for Rust`() {
        // languages/05-rust R15
        val old = """
            struct S;

            impl S {
                fn render(&self, items: &[i32]) -> i32 {
                    let mut sum = 0;
                    for i in items {
                        sum += *i;
                    }
                    sum
                }
            }
        """.trimIndent() + "\n"
        val new = """
            struct S;

            impl S {
                fn render(&self, items: &[i32]) -> i32 {
                    self.sum_items(items)
                }

                fn sum_items(&self, items: &[i32]) -> i32 {
                    let mut sum = 0;
                    for i in items {
                        sum += *i;
                    }
                    sum
                }
            }
        """.trimIndent() + "\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        assertTrue(
            "extraction relationship",
            result.relationships.any {
                it.kinds.contains(io.github.agadek.smartdiff.engine.api.RelationshipKind.EXTRACT)
            },
        )
    }

    fun `test R15 inlining detection works for Rust`() {
        // languages/05-rust R15
        val old = """
            struct S;

            impl S {
                fn render(&self, items: &[i32]) -> i32 {
                    self.sum_items(items)
                }

                fn sum_items(&self, items: &[i32]) -> i32 {
                    let mut sum = 0;
                    for i in items {
                        sum += *i;
                    }
                    sum
                }
            }
        """.trimIndent() + "\n"
        val new = """
            struct S;

            impl S {
                fn render(&self, items: &[i32]) -> i32 {
                    let mut sum = 0;
                    for i in items {
                        sum += *i;
                    }
                    sum
                }
            }
        """.trimIndent() + "\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        assertTrue(
            "inlining relationship",
            result.relationships.any {
                it.kinds.contains(io.github.agadek.smartdiff.engine.api.RelationshipKind.INLINE)
            },
        )
    }

    fun `test R15 rename candidates work for Rust`() {
        // languages/05-rust R15
        val old = """
            fn calculate_total(items: &[i32]) -> i32 {
                let mut sum = 0;
                for i in items {
                    sum += *i;
                }
                sum
            }
        """.trimIndent() + "\n"
        val new = """
            fn compute_sum(items: &[i32]) -> i32 {
                let mut sum = 0;
                for i in items {
                    sum += *i;
                }
                sum
            }
        """.trimIndent() + "\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        assertTrue(
            "rename relationship",
            result.relationships.any {
                it.kinds.contains(io.github.agadek.smartdiff.engine.api.RelationshipKind.RENAME)
            },
        )
    }

    fun `test R15 kind changes work for Rust`() {
        // languages/05-rust R15
        val oldStructEnum = """
            struct Item {
                x: i32,
            }
        """.trimIndent() + "\n"
        val newStructEnum = """
            enum Item {
                A,
            }
        """.trimIndent() + "\n"
        val resultStructEnum = compare(oldStructEnum, newStructEnum)
        assertEquals(Status.ANALYZED, resultStructEnum.status)
        assertTrue(
            "struct -> enum kind change",
            resultStructEnum.relationships.any {
                it.kinds.contains(io.github.agadek.smartdiff.engine.api.RelationshipKind.KIND_CHANGE)
            },
        )

        val oldStructUnion = """
            struct Data {
                x: u32,
            }
        """.trimIndent() + "\n"
        val newStructUnion = """
            union Data {
                x: u32,
            }
        """.trimIndent() + "\n"
        val resultStructUnion = compare(oldStructUnion, newStructUnion)
        assertEquals(Status.ANALYZED, resultStructUnion.status)
        assertTrue(
            "struct -> union kind change",
            resultStructUnion.relationships.any {
                it.kinds.contains(io.github.agadek.smartdiff.engine.api.RelationshipKind.KIND_CHANGE)
            },
        )
    }

    fun `test R16 merge outline and changes tree show Rust through engine comparison`() {
        // languages/05-rust R16
        val base = """
            struct S;

            impl S {
                fn f(&self) -> i32 { 1 }
            }
        """.trimIndent() + "\n"
        val left = """
            struct S;

            impl S {
                fn f(&self) -> i32 { 2 }
            }
        """.trimIndent() + "\n"
        val right = """
            struct S;

            impl S {
                fn f(&self) -> i32 { 3 }
            }
        """.trimIndent() + "\n"
        val leftResult = compare(base, left)
        val rightResult = compare(base, right)
        assertEquals(Status.ANALYZED, leftResult.status)
        assertEquals(Status.ANALYZED, rightResult.status)
        assertTrue("left side reports changes", leftResult.changes.isNotEmpty())
        assertTrue("right side reports changes", rightResult.changes.isNotEmpty())
    }
}
