# Groovy

Status: implemented, 2026-11-15

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports Groovy files, through the IDE's Groovy parser, when the Groovy plugin is installed.

**Use case:** A developer reviews a Groovy change and sees which classes, traits, methods and properties changed, including in Gradle build scripts.

## Requirements

- **R1** Groovy support works when the IDE has the Groovy plugin, and the plugin loads without it.
- **R2** Classes, interfaces, traits, enums and annotation types are [declarations](../../GLOSSARY.md#declaration), including named nested and inner types, with [kind](../../GLOSSARY.md#kind) `TRAIT` for traits and the same kinds as Java for the others.
- **R3** Methods, constructors, fields, properties, enum constants and class initializers are declarations.
- **R4** A field declared without an access modifier is a Groovy property and has kind `PROPERTY`.
- **R5** In a script file, top-level methods and the script's classes are top-level declarations; top-level statements are not declarations and stay in the [file region](../../GLOSSARY.md#file-region); a script's top-level variable declarations are not declarations.
- **R6** Closures, anonymous classes, local variables and local classes are content of the declaration that contains them.
- **R7** A [signature](../../GLOSSARY.md#signature) lists parameter types as written; an untyped parameter appears as `def`, so overloads that differ only by arity get distinct signatures.
- **R8** Whitespace inside a line is never a change in Groovy; adding or removing a line break between tokens inside a declaration is a change, so reformatting inside a line is [formatting-only](../../GLOSSARY.md#formatting-only) and joining or splitting lines inside a declaration is a change.
- **R9** Errors in Groovydoc are not [parse errors](../../GLOSSARY.md#parse-error).
- **R10** `.gradle` files are Groovy files and get the Groovy outline for their methods and classes. Build-script [sections](#requirements) are produced only for build scripts (R11).
- **R11** Build-script sections are produced only for files whose name ends in `.gradle`, including `settings.gradle` and `init.gradle`. Plain `.groovy` scripts and `.gvy` files are unchanged. The file name is the one given to the provider; without a file name a file gets no sections.
- **R12** In a build script, a top-level statement that is a method call whose last argument is a closure is a declaration of kind `SECTION`, including command-expression forms such as `task hello { }` and calls such as `tasks.register('hello') { }`.
- **R13** A section's name is the callee as written, followed by the first string-literal argument when there is one, separated by a space (for example `plugins`, `dependencies`, `android`, `tasks.register hello`, `task hello`, `tasks.named test`). When a string-literal argument is absent, the first argument used as the name in command form (such as `hello` in `task hello { }`) is used. Generic type arguments are not part of the name. The exact naming rule is stated in the [details file](08-groovy-details.md).
- **R14** Two sections with the same name in one container follow the matcher's rules for repeated keys, with no new matching rule.
- **R15** Only top-level blocks are sections in this part. Nested blocks, such as `android { defaultConfig { } }`, are content of their section.
- **R16** Other top-level statements, such as assignments (`version = '1.0'`, `group = ...`) and calls without a closure (`apply plugin: 'java'`), stay in the file region. A `buildscript` block is a section like any other. Top-level methods and classes in a `.gradle` file keep their existing declarations.
- **R17** A section has no [signature](../../GLOSSARY.md#signature). The outline shows the section name and kind.
- **R18** Sections use the file's existing whitespace policy: whitespace inside a line is [formatting-only](../../GLOSSARY.md#formatting-only), and adding or removing a line break between tokens inside a section is a change.
- **R19** Editing content inside a section marks the section changed; moving a whole section marks it moved; an added or removed section is shown as added or removed.
- **R20** Rename candidates for sections apply only through the generic matching rule with no new code. Sections take no part in extraction or inlining detection.

## Out of scope

- Nested blocks as sections (for example `android { defaultConfig { } }` inside a section).
- Relationship resolution between Groovy declarations.

## References

- [Details: design and tests](08-groovy-details.md)
- [Technical specification §6.15](../../TECHNICAL-SPECIFICATION.md#615-groovy-provider)
- [Format coverage plan §2.1, §5](../../FORMAT-COVERAGE.md#21-group-a-free-psi-on-the-current-build-target)
