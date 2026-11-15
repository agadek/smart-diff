# Kotlin

Status: implemented, 2026-11-15

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports Kotlin files, through the IDE's Kotlin parser, in K1 and K2 mode.

**Use case:** A developer reviews a Kotlin change and sees which classes, functions and properties changed.

## Requirements

- **R1** Kotlin support works when the IDE has the Kotlin plugin, and the plugin loads without it.
- **R2** Classes, interfaces, enums, annotation classes, objects and companion objects are [declarations](../../GLOSSARY.md#declaration).
- **R3** Functions, secondary constructors, properties, enum entries, type aliases and initializer blocks are declarations.
- **R4** An unnamed companion object is named `Companion`.
- **R5** Local functions and properties, lambdas and object literals are not declarations.
- **R6** The primary constructor and its parameters are part of the class.
- **R7** A function's [signature](../../GLOSSARY.md#signature) starts with its receiver type, then its parameter types.
- **R8** A `vararg` parameter and a plain parameter of the same type give different signatures.
- **R9** An extension's receiver is shown before its name: `function Date.formatIsoDate()`.
- **R10** Adding or removing a line break between tokens inside a declaration is a change, since line breaks can change how Kotlin code is read; the line break before a declaration is layout ([declarations/02-change-classification](../declarations/02-change-classification.md) R14).
- **R11** Other whitespace changes are [formatting-only](../../GLOSSARY.md#formatting-only).
- **R12** Build-script sections are produced only for files whose name ends in `.gradle.kts`, including `settings.gradle.kts` and `init.gradle.kts`. Other `.kts` scripts, such as `.main.kts`, are unchanged. The file name is the one given to the provider; without a file name a file gets no sections. A `.gradle.kts` build script is parsed with the fixed light-file name `SmartDiff.gradle.kts`.
- **R13** In a `.gradle.kts` build script, a top-level script initializer whose expression is a call with a trailing lambda, including a qualified call such as `tasks.register("hello") { }`, is a declaration of kind `SECTION`.
- **R14** A section's name follows the same naming rule as [Groovy build-script sections](08-groovy.md): the callee path as written, followed by the name argument of the call when there is one, separated by a space (for example `plugins`, `dependencies`, `tasks.register hello`, `tasks.named test`, `tasks.withType.configureEach`, `project :app`). Generic call-site type arguments are removed from the callee. The exact naming rule is stated in [Groovy: details](08-groovy-details.md).
- **R15** Two sections with the same name in one container follow the matcher's rules for repeated keys, with no new matching rule.
- **R16** Only top-level blocks are sections in this part. Nested blocks inside a section are content of that section.
- **R17** Other top-level statements, such as assignments and calls without a trailing lambda, stay in the [file region](../../GLOSSARY.md#file-region). A `buildscript` block is a section like any other. Top-level functions and classes in a `.gradle.kts` file keep their existing declarations.
- **R18** A section has no [signature](../../GLOSSARY.md#signature). The outline shows the section name and kind.
- **R19** Sections use the file's existing whitespace policy: whitespace inside a line is [formatting-only](../../GLOSSARY.md#formatting-only), and adding or removing a line break between tokens inside a section is a change.
- **R20** Editing content inside a section marks the section changed; moving a whole section marks it moved; an added or removed section is shown as added or removed.
- **R21** Rename candidates for sections apply only through the generic matching rule with no new code. Sections take no part in extraction or inlining detection.
- **R22** The line breaks and blank lines between top-level sections are layout, so adding, removing or reordering a section does not report other sections or the file region.

## References

- [Groovy build-script sections](08-groovy.md) and [details](08-groovy-details.md) (shared naming rule)
- [Technical specification §6.5 and §6.6](../../TECHNICAL-SPECIFICATION.md#65-kotlin-provider)
