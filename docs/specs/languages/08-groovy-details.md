# Groovy: details

Status: implemented, 2026-11-14

**Feature:** [Groovy](08-groovy.md)

## Design

- **Language id and PSI.** The provider supports `Groovy` (`GroovyLanguage.INSTANCE.id`) and uses Groovy PSI (`GroovyFile`, `GrTypeDefinition`, `GrMethod`, `GrField`, `GrEnumConstant`, `GrClassInitializer`). It works when the Groovy plugin (`org.intellij.groovy`) is installed and the plugin loads without it (R1). Calls into Groovy PSI stay in the provider; no separate interop class is needed because the provider does not use Scala-library or verifier-sensitive internal types.
- **File creation.** Files are built with `PsiFileFactory` in `GroovyLanguage.INSTANCE`. The file name keeps the request's extension when it is `.groovy`, `.gradle` or `.gvy` and otherwise defaults to `SmartDiff.groovy`, so `.gradle` scripts parse as Groovy files (R10).
- **Top level.** In a class file, the file's type definitions are the top-level declarations. In a script file, top-level script methods and the script's type definitions are top-level declarations, in source order; top-level statements and script variable declarations are not declarations and remain in the file region (R5, R10). The file region holds all source not covered by a top-level declaration, so script statements stay there.
- **Kinds.** R2, R3, R4:

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

  A `GrField` declared without an access modifier is a Groovy property and has kind `PROPERTY` (R4). A named `GrField` with an explicit access modifier is a `FIELD`.
- **Members.** A type definition's members are the declarations written directly in its body, in source order: named nested and inner type definitions, methods, constructors, fields, enum constants and class initializers. Anonymous classes inside the body are not declarations and stay content (R2, R6), as do closures, local variables and local classes inside a member (R6).
- **Records.** On the bundled Groovy PSI of IntelliJ IDEA Community 2025.1.3, `record Point(int x, int y) {}` parses as a `GrTypeDefinition` (`GrRecordDefinitionImpl`) but `isRecord` returns `false` and the provider classifies it as `CLASS`; records are therefore not treated as `RECORD` declarations in this target. If a later bundled Groovy PSI reports records correctly, they can be added as `RECORD` declarations.
- **Signatures.** A signature is the parameter types as written, joined with `,`, with whitespace and comments removed (R7), using the same normalization as Java and Kotlin for labels. An untyped parameter has no declared type element in the Groovy PSI; it appears as `def` in the signature, so `foo(x)` and `foo(x, y)` differ as `def` and `def,def`. A typed parameter keeps its declared type as written. The display signature follows the same types with readable spacing.
- **Whitespace.** Groovy statements end at a line break, like Kotlin, so the provider uses `LINE_BREAKS_SIGNIFICANT` (R8). Whitespace inside a line is dropped, so reformatting inside a line is formatting-only. A line break added or removed between tokens inside a declaration is a change, so joining or splitting lines inside a declaration is a change. The line break before a declaration is leading layout owned by the declaration ([declarations/02-change-classification](../declarations/02-change-classification.md) R14).
- **Parse errors and Groovydoc.** Parse errors in code mark the innermost declaration or the file region as unanalyzed, as for other PSI providers. Parse-error elements inside Groovydoc are not parse errors (R9); the comment's tokens are still compared.
- **Gradle scripts.** `.gradle` files parse as Groovy files and get the same outline for their top-level methods and classes (R10). Top-level build-script blocks such as `plugins { }` and `dependencies { }` are statements or calls in a script, not declarations in this part, so they remain in the file region until M2b models them as sections.

## Tests

- `GroovyDeclarationProviderTest` (platform test, text-built files):
  - R1, R2, R3, R4: class, interface, trait, enum, annotation type, methods, constructors, fields, properties, enum constants and initializers are declarations with the right kinds and names.
  - R5: a script file exposes top-level methods and classes as top-level declarations, while top-level statements and script variable declarations stay in the file region.
  - R6: closures, anonymous classes, local variables and local classes are not declarations.
  - R7: parameter types are as written, an untyped parameter is `def`, and overloads that differ only by arity have distinct signatures.
  - R8: reformatting inside a line is formatting-only, and joining or splitting lines inside a declaration is a change.
  - R9: errors in Groovydoc do not make a declaration unanalyzed.
  - R10: a `.gradle` script parses as Groovy and exposes its top-level methods and classes; its top-level blocks stay in the file region.
- Golden fixtures under `src/test/testData/groovy/<case>/` with `GoldenFixturesTest` (`languages/08-groovy`): method added, removed, changed, moved, signature changed, overloads, a class with fields, properties and constructors, a trait, an enum, a script with top-level methods and statements, a `.gradle` file, formatting-only reformat, a line-join that is a change, parse error on one side, identical and empty.
- Merge fixtures under `src/test/testData/groovy/merge/<case>/` with `MergeFixturesTest`.
- Corpus validation: `CorpusValidationTest` compares modified `.groovy` and `.gradle` files in listed commits the same way as other languages. No bundled offline Groovy corpus is required; running it needs a corpus list passed to the test property, as for the other languages.
- Structure-view fallback: `StructureViewCases` and the structure-view provider and spike tests keep Groovy as a direct fallback case; `GoldenFixturesTest` runs the `groovy/` fixtures through the Groovy provider; the `structure-view/groovy/` fixtures run through the structure-view provider directly in `StructureViewDeclarationProviderTest`, so they still test the fallback; `StructureViewDeclarationProviderTest` also asserts that a Groovy file goes to the Groovy provider and not the fallback.

## Open questions

- **M2b:** model Gradle build-script top-level blocks (`plugins { }`, `dependencies { }`, tasks and similar calls) as `SECTION` declarations in `.gradle` and `.gradle.kts` files once the `SECTION` kind is available.
- **Relationships:** reference resolution and relationship grouping for Groovy declarations.
