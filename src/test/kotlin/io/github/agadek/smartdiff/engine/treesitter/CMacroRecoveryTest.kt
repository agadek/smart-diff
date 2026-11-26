package io.github.agadek.smartdiff.engine.treesitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The macro patterns of engines/06 R7 to R18, on text only: what macro recovery reads as blank space. */
class CMacroRecoveryTest {

    /** The words of [text] that macro recovery reads as blank space, in order. */
    private fun masked(text: String): List<String> {
        val recovery = CMacroRecovery.recover(text) ?: return emptyList()
        assertEquals("the recovered text keeps the length", text.length, recovery.text.length)
        for (i in text.indices) {
            if (text[i] == '\n' || text[i] == '\r') assertEquals("line breaks stay at $i", text[i], recovery.text[i])
            else assertTrue("offset $i is the original or a space", recovery.text[i] == text[i] || recovery.text[i] == ' ')
        }
        return recovery.masks.map { text.substring(it.range.start, it.range.end).trim().replace(Regex("\\s+"), " ") }
    }

    private fun patterns(text: String): List<MacroPattern> = CMacroRecovery.recover(text)?.masks?.map { it.kind }.orEmpty()

    // engines/06 R7
    @Test
    fun `an include guard around the whole file`() {
        val text = "#ifndef LIB_H\n#define LIB_H\nint f(void);\n#endif /* LIB_H */\n"
        assertEquals(listOf("#ifndef LIB_H", "#endif /* LIB_H */"), masked(text))
        assertEquals(listOf(MacroPattern.INCLUDE_GUARD, MacroPattern.INCLUDE_GUARD), patterns(text))
    }

    // engines/06 R7
    @Test
    fun `a conditional that does not wrap the whole file is no include guard`() {
        assertEquals(emptyList<String>(), masked("#ifndef A\n#define A\n#endif\nint f(void);\n"))
        assertEquals(emptyList<String>(), masked("#ifndef A\n#define A\nint f(void);\n#else\nint g(void);\n#endif\n"))
    }

    // engines/06 R8
    @Test
    fun `an extern C wrapper under __cplusplus`() {
        val text = "#ifdef __cplusplus\nextern \"C\" {\n#endif\nint f(void);\n#ifdef __cplusplus\n}\n#endif\n"
        assertEquals(listOf("#ifdef __cplusplus extern \"C\" { #endif", "#ifdef __cplusplus } #endif"), masked(text))
    }

    // engines/06 R9
    @Test
    fun `attribute macros before a declaration`() {
        assertEquals(listOf("FMT_API"), masked("FMT_API void f();\n"))
        assertEquals(listOf("FMT_API", "FMT_CONSTEXPR"), masked("FMT_API FMT_CONSTEXPR auto g() -> int;\n"))
        assertEquals(listOf("FMT_API"), masked("FMT_API HANDLE open_file(const char *name);\n"))
        assertEquals(listOf("LIB_DEPRECATED(\"use g\")"), masked("LIB_DEPRECATED(\"use g\") static int h(void);\n"))
        assertEquals(listOf("FMT_INLINE_VARIABLE"), masked("constexpr FMT_INLINE_VARIABLE uint32_t invalid = 0;\n"))
        assertEquals(listOf("FMT_NO_UNIQUE_ADDRESS"), masked("struct A { FMT_NO_UNIQUE_ADDRESS Allocator alloc_; };\n"))
        assertEquals(listOf("FMT_EXPORT"), masked("FMT_EXPORT template <typename T> class store {};\n"))
    }

    // engines/06 R9
    @Test
    fun `an attribute macro between class and the class name`() {
        assertEquals(listOf("LEVELDB_EXPORT"), masked("struct LEVELDB_EXPORT Options {\n  int x;\n};\n"))
        assertEquals(listOf("FMT_SO_VISIBILITY(\"default\")"), masked("class FMT_SO_VISIBILITY(\"default\") format_error : public base {};\n"))
        assertEquals(listOf(MacroPattern.CLASS_ATTRIBUTE), patterns("struct LEVELDB_EXPORT Options {\n};\n"))
    }

    // engines/06 R9
    @Test
    fun `an attribute macro before a constructor once the file uses it as an attribute`() {
        val text = "struct node {\n  FMT_CONSTEXPR node(int v) : value(v) {}\n  FMT_CONSTEXPR int get() const { return value; }\n  int value;\n};\n"
        assertEquals(listOf("FMT_CONSTEXPR", "FMT_CONSTEXPR"), masked(text))
        assertEquals(emptyList<String>(), masked("struct node {\n  NODE_CTOR node(int v) {}\n};\n"))
    }

    // engines/06 R9
    @Test
    fun `a capitalized type is not an attribute macro`() {
        assertEquals(emptyList<String>(), masked("DWORD count;\nstatic DWORD total = 0;\nHANDLE open_file(const char *name);\nFILE *out;\n"))
        assertEquals(emptyList<String>(), masked("UINT32 const limit = 4;\n"))
    }

    // engines/06 R10
    @Test
    fun `an attribute macro after a parameter list`() {
        assertEquals(listOf("JV_PRINTF_LIKE(1, 2)"), masked("jv jv_string_fmt(const char*, ...) JV_PRINTF_LIKE(1, 2);\n"))
        assertEquals(listOf("FMT_OVERRIDE"), masked("struct S : B {\n  void run() const FMT_OVERRIDE;\n};\n"))
        assertEquals(listOf(MacroPattern.TRAILING_ATTRIBUTE), patterns("void f(int) NORETURN;\n"))
    }

    // engines/06 R10, R11: inside a function body, a macro after `)` is a statement, not an attribute
    @Test
    fun `an attribute after a parameter list is not looked for in a function body`() {
        assertEquals(emptyList<String>(), masked("void f(int x) {\n  if (x) YY_BUFFER->state = 1;\n}\n"))
    }

    // engines/06 R11
    @Test
    fun `a macro invocation on lines of its own`() {
        val text = "FMT_BEGIN_NAMESPACE\nint f();\nFMT_PRAGMA_CLANG(diagnostic push)\nOP(LOADK, CONSTANT, 1,\n   1)\nFMT_END_NAMESPACE\n"
        assertEquals(listOf("FMT_BEGIN_NAMESPACE", "FMT_PRAGMA_CLANG(diagnostic push)", "OP(LOADK, CONSTANT, 1, 1)", "FMT_END_NAMESPACE"), masked(text))
        assertTrue(patterns(text).all { it == MacroPattern.MACRO_LINE })
    }

    // engines/06 R11
    @Test
    fun `an invocation with a semicolon, an enumerator and a return type on its own line are kept`() {
        assertEquals(emptyList<String>(), masked("DECLARE_THING(a);\nint f();\n"))
        assertEquals(emptyList<String>(), masked("typedef enum {\n  ONLY_VALUE\n} kind;\n"))
        assertEquals(emptyList<String>(), masked("enum {\n  FIRST,\n  LAST\n};\n"))
        assertEquals(emptyList<String>(), masked("static int\nCOUNT\n(void);\nDWORD\nGetCount(void)\n{\n  return 0;\n}\n"))
    }

    // engines/06 R12
    @Test
    fun `the head of a macro that takes a block in a function body`() {
        val text = "void f(jv a) {\n  jv_array_foreach(a, i, x) {\n    use(x);\n  }\n  FMT_TRY {\n    g();\n  }\n  FMT_CATCH(...) {}\n}\n"
        assertEquals(listOf("jv_array_foreach(a, i, x)", "FMT_TRY", "FMT_CATCH(...)"), masked(text))
        assertTrue(patterns(text).all { it == MacroPattern.BLOCK_MACRO })
    }

    // engines/06 R12
    @Test
    fun `statements and constructors with a block are kept`() {
        assertEquals(emptyList<String>(), masked("void f(int x) {\n  if (x) {\n  }\n  while (x) {\n  }\n  switch (x) {\n  }\n}\n"))
        assertEquals(emptyList<String>(), masked("class Box {\n  Box(int x) {\n  }\n};\n"))
    }

    // engines/06 R13
    @Test
    fun `a macro call that is a whole template parameter`() {
        assertEquals(listOf(", FMT_ENABLE_IF(sizeof(To) > sizeof(From))"), masked("template <typename To, typename From, FMT_ENABLE_IF(sizeof(To) > sizeof(From))>\nauto bit_cast(const From& from) -> To;\n"))
        assertEquals(listOf("FMT_ENABLE_IF(is_char<T>::value),"), masked("template <FMT_ENABLE_IF(is_char<T>::value), typename T>\nvoid f(T);\n"))
        assertEquals(emptyList<String>(), masked("int x = call(a, B(c), d);\n"))
    }

    // engines/06 R14
    @Test
    fun `a macro between if and its condition`() {
        assertEquals(listOf("FMT_CONSTEXPR20"), masked("void f() {\n  if FMT_CONSTEXPR20 (need_copy<T>::value) g();\n}\n"))
    }

    // engines/06 R15
    @Test
    fun `a header argument in an if line`() {
        val text = "#if FMT_HAS_INCLUDE(<version>) && __has_include( <meta> )\n#include <version>\n#endif\n"
        assertEquals(listOf("<version>", "<meta>"), masked(text))
        assertEquals(emptyList<String>(), masked("#include <version>\n#define CHECK(x) (<x>)\n"))
    }

    // engines/06 R16
    @Test
    fun `conditionals in a function body keep the first branch`() {
        val text = "int f(int x) {\n#ifdef WIN32\n  if (a(x))\n#else\n  if (b(x))\n#endif\n    return 1;\n  return 0;\n}\n"
        assertEquals(listOf("#ifdef WIN32", "#else if (b(x)) #endif"), masked(text))
        assertEquals(emptyList<String>(), masked("#ifdef WIN32\nint f(void) { return 1; }\n#else\nint f(void) { return 0; }\n#endif\n"))
    }

    // engines/06 R17
    @Test
    fun `a conditional around specifiers only`() {
        val text = "#ifndef WIN32\nstatic\n#endif\nvoid f(void) {}\n#if defined(__clang__)\n__attribute__((no_sanitize(\"undefined\")))\n#endif\nint g(void);\n"
        assertEquals(listOf("#ifndef WIN32", "#endif", "#if defined(__clang__)", "#endif"), masked(text))
        assertEquals(emptyList<String>(), masked("#ifndef WIN32\nstatic int x;\n#endif\n"))
        assertEquals(emptyList<String>(), masked("#ifdef A\nstatic\n#else\nextern\n#endif\nint x;\n"))
    }

    // engines/06 R18
    @Test
    fun `the line breaks of a masked directive stay content`() {
        val text = "#ifndef LIB_H\n#define LIB_H\nint f(void);\n#endif\n"
        val recovery = CMacroRecovery.recover(text)!!
        assertEquals(setOf(text.indexOf('\n'), text.length - 1), recovery.lineBreaks)
        val continued = "void f() {\n#if A && \\\n    B\n  g();\n#endif\n}\n"
        val breaks = CMacroRecovery.recover(continued)!!.lineBreaks
        assertTrue(continued.indexOf("\\\n") + 1 in breaks)
        assertTrue(continued.indexOf("B\n") + 1 in breaks)
    }

    // engines/06 R4, R5: strings, comments and directives are never read as macros
    @Test
    fun `strings, comments and macro definitions are left alone`() {
        assertNull(CMacroRecovery.recover("// FMT_API void f();\n/* FMT_BEGIN_NAMESPACE\n*/\nconst char *s = \"FMT_API void f();\";\n#define FMT_API __attribute__((visibility(\"default\")))\n"))
    }

    // engines/06 R6
    @Test
    fun `only attribute macros before a declaration belong to it`() {
        val recovery = CMacroRecovery.recover("FMT_BEGIN_NAMESPACE\nFMT_API void f();\n#ifndef WIN32\nstatic\n#endif\nvoid g(void) {}\n")!!
        val text = "FMT_BEGIN_NAMESPACE\nFMT_API void f();\n#ifndef WIN32\nstatic\n#endif\nvoid g(void) {}\n"
        assertEquals(listOf("FMT_API", "#ifndef WIN32"), recovery.prefixes.map { text.substring(it.start, it.end) })
    }
}
