package io.github.agadek.smartdiff.engine.api

import junit.framework.TestCase

class DeclarationKindLabelTest : TestCase() {

    fun `test declaration kind labels`() {
        assertEquals("const", DeclarationKind.CONSTANT.label())
        assertEquals("enum constant", DeclarationKind.ENUM_CONSTANT.label())
        assertEquals("type alias", DeclarationKind.TYPE_ALIAS.label())
        assertEquals("annotation type", DeclarationKind.ANNOTATION_TYPE.label())
        assertEquals("struct", DeclarationKind.STRUCT.label())
        assertEquals("union", DeclarationKind.UNION.label())
        assertEquals("macro", DeclarationKind.MACRO.label())
        assertEquals("class", DeclarationKind.CLASS.label())
        assertEquals("function", DeclarationKind.FUNCTION.label())
    }
}
