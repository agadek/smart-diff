# Groovy: details

Status: implemented, 2026-11-15

**Feature:** [Groovy](08-groovy.md)

## Design

- **Language id and PSI.** The provider supports `Groovy` (`GroovyLanguage.INSTANCE.id`) and uses Groovy PSI (`GroovyFile`, `GrTypeDefinition`, `GrMethod`, `GrField`, `GrEnumConstant`, `GrClassInitializer`, and `GrMethodCall` for build-script sections). It works when the Groovy plugin (`org.intellij.groovy`) is installed and the plugin loads without it (R1). Calls into Groovy PSI stay in the provider.
- **File creation.** Files are built with `PsiFileFactory` in `GroovyLanguage.INSTANCE`. The file name keeps the request's extension when it is `.groovy`, `.gradle` or `.gvy` and otherwise defaults to `SmartDiff.groovy`, so `.gradle` scripts parse as Groovy files (R10). Build-script sections are produced only when the request file name ends in `.gradle` (R11); without a file name a file gets no sections.
- **Top level.** In a class file, the file's type definitions are the top-level declarations. In a script file, top-level script methods, the script's type definitions and, for build scripts (R11), build-script section calls are top-level declarations, in source order; other top-level statements and script variable declarations stay in the file region (R5, R10, R16). The file region holds all source not covered by a top-level declaration.
- **Kinds.** R2, R3, R4, R12:

  | PSI element | Kind | Name | Signature |
  | --- | --- | --- | --- |
  | `GrTypeDefinition` (class) | `CLASS` | name | — |
  | `GrTypeDefinition` (interface) | `INTERFACE` | name | — |
  | `GrTypeDefinition` (trait) | `TRAIT` | name | — |
  | `GrTypeDefinition` (enum) | `ENUM` | name | — |
  | `GrTypeDefinition` (annotation type) | `ANNOTATION_TYPE` | name | — |
  | `GrMethod` (constructor) | `CONSTRUCTOR` | name | parameter types |
  | `GrMethod` (not constructor) | `METHOD` | name | parameter types |
  | `GrField` (Groovy property) | `PROPERTY` | name | — |
  | `GrField` (not a property) | `FIELD` | name | — |
  | `GrEnumConstant` | `ENUM_CONSTANT` | name | — |
  | `GrClassInitializer` | `INITIALIZER` | `""`, or `"static"` for a static initializer | — |
  | Build-script call with a closure (R12) | `SECTION` | callee and optional name argument (R13) | — |

  A `GrField` declared without an access modifier is a Groovy property and has kind `PROPERTY` (R4). A named `GrField` with an explicit access modifier is a `FIELD`.
- **Members.** A type definition's members are the declarations written directly in its body, in source order: named nested and inner type definitions, methods, constructors, fields, enum constants and class initializers. Anonymous classes inside the body are not declarations and stay content (R2, R6), as do closures, local variables and local classes inside a member (R6).
- **Records.** On the bundled Groovy PSI of IntelliJ IDEA Community 2025.1.3, `record Point(int x, int y) {}` parses as a `GrTypeDefinition` (`GrRecordDefinitionImpl`) but `isRecord` returns `false` and the provider classifies it as `CLASS`; records are therefore not treated as `RECORD` declarations in this target. If a later bundled Groovy PSI reports records correctly, they can be added as `RECORD` declarations.
- **Signatures.** A signature is the parameter types as written, joined with `,`, with whitespace and comments removed (R7), using the same normalization as Java and Kotlin for labels. An untyped parameter has no declared type element in the Groovy PSI; it appears as `def` in the signature, so `foo(x)` and `foo(x, y)` differ as `def` and `def,def`. A typed parameter keeps its declared type as written. The display signature follows the same types with readable spacing. Sections have no signature (R17).
- **Build-script sections (M2b).** In a file whose name ends in `.gradle` (R11), a top-level script statement that is a method call whose last argument is a closure is a `SECTION` declaration (R12). This includes calls with a trailing closure (`dependencies { }`, `tasks.register('hello') { }`, `tasks.register<Copy>('copy') { }`), qualified calls, and command-expression forms (`task hello { }`). The section's range is the whole statement, from callee through the closing brace.
- **Section names (R13).** The name is the callee as written, with generic type arguments removed from the invoked expression, followed when present by a name argument separated by a space. The name argument is the first string-literal expression argument's text without quotes when there is one; otherwise, for command forms with no string-literal argument, it is the text of the first expression argument as written (for example `hello` in `task hello { }` or `clean` in `task clean(type: Delete) { }`). Examples: `plugins`, `dependencies`, `android`, `allprojects`, `buildscript`, `tasks.register hello`, `tasks.named test`, `task hello`. Generic type arguments such as `<Copy>` in `tasks.register<Copy>("x")` are not part of the name.
- **Repeated sections (R14).** Two sections with the same name follow the matcher's rules for repeated keys, with no new matching rule.
- **Nesting (R15).** Only top-level blocks are sections in this part. Nested blocks inside a section are content of that section.
- **Other statements (R16).** Assignments (`version = '1.0'`, `group = ...`), calls without a closure (`apply plugin: 'java'`), and other statements that are not section calls stay in the file region. A `buildscript` block is a section like any other. Top-level methods and classes keep their existing declarations.
- **Whitespace.** Groovy statements end at a line break, like Kotlin, so the provider uses `LINE_BREAKS_SIGNIFICANT` (R8, R18). Whitespace inside a line is dropped, so reformatting inside a line is formatting-only. A line break added or removed between tokens inside a declaration or section is a change. The line break before a declaration or section is leading layout owned by it ([declarations/02-change-classification](../declarations/02-change-classification.md) R14).
- **Parse errors and Groovydoc.** Parse errors in code mark the innermost declaration or the file region as unanalyzed, as for other PSI providers. Parse-error elements inside Groovydoc are not parse errors (R9); the comment's tokens are still compared.
- **Relationships (R20).** Rename candidates for sections apply only through the generic matching rule with no new code. Sections take no part in extraction or inlining detection.

## Tests

- `GroovyDeclarationProviderTest` (platform test, text-built files):
  - R1, R2, R3, R4: class, interface, trait, enum, annotation type, methods, constructors, fields, properties, enum constants and initializers are declarations with the right kinds and names.
  - R5: a script file exposes top-level methods and classes as top-level declarations, while top-level statements and script variable declarations stay in the file region.
  - R6: closures, anonymous classes, local variables and local classes are not declarations.
  - R7: parameter types are as written, an untyped parameter is `def`, and overloads that differ only by arity have distinct signatures.
  - R8, R18: reformatting inside a line is formatting-only, and joining or splitting lines inside a declaration or section is a change.
  - R9: errors in Groovydoc do not make a declaration unanalyzed.
  - R10, R11, R12, R13, R16, R17: a `.gradle` build script exposes top-level methods and classes and section declarations with the right names; non-build `.groovy` scripts get no sections; assignments and calls without a closure stay in the file region; sections have no signature.
  - R14, R15, R19: repeated sections, nested blocks as content, and offsets for sections.
- Golden fixtures under `src/test/testData/groovy/<case>/` with `GoldenFixturesTest` (`languages/08-groovy`): method added, removed, changed, moved, signature changed, overloads, a class with fields, properties and constructors, a trait, an enum, a script with top-level methods and statements, formatting-only reformat, a line-join that is a change, parse error on one side, identical and empty. Build-script fixtures for `.gradle` include dependency added inside `dependencies` (section changed), block added, block removed, blocks reordered (moved), two `dependencies` blocks, task registration renamed, formatting-only reformat inside a block, line split inside a block as a change, identical and empty.
- Merge fixtures under `src/test/testData/groovy/merge/<case>/` with `MergeFixturesTest`, including build-script cases where two sides edit different sections and both edit the same section.
- Corpus validation: `CorpusValidationTest` compares modified `.groovy` and `.gradle` files in listed commits the same way as other languages. No bundled offline Groovy corpus is required; running it needs a corpus list passed to the test property, as for the other languages.
- Structure-view fallback: `StructureViewCases` and the structure-view provider and spike tests keep Groovy as a direct fallback case; `GoldenFixturesTest` runs the `groovy/` fixtures through the Groovy provider; the `structure-view/groovy/` fixtures run through the structure-view provider directly in `StructureViewDeclarationProviderTest`, so they still test the fallback; `StructureViewDeclarationProviderTest` also asserts that a Groovy file goes to the Groovy provider and not the fallback.

## Open questions

- **Nested sections:** model nested blocks (for example `android { defaultConfig { } }`) as section declarations inside their parent section.
- **Relationships:** reference resolution and relationship grouping for Groovy declarations.
