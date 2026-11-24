# JavaScript and TypeScript

Status: implemented, 2026-11-24

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports JavaScript and TypeScript files, JSX and TSX included, through the IDE's JavaScript plugin, when the IDE has it.

**Use case:** A developer reviews a JavaScript or TypeScript change in WebStorm, IntelliJ IDEA Ultimate or another JetBrains IDE with the JavaScript plugin, and sees which classes, functions, methods, interfaces and types changed, without reading every changed line, while a change a missing semicolon makes meaningful is never called [formatting-only](../../GLOSSARY.md#formatting-only).

## Requirements

- **R1** JavaScript and TypeScript support works when the IDE has the JavaScript plugin, and the plugin loads without it.
- **R2** `.js`, `.mjs`, `.cjs`, `.jsx`, `.ts`, `.mts`, `.cts` and `.tsx` files are a [supported language](../../GLOSSARY.md#supported-language) when the JavaScript plugin is present.
- **R3** Function declarations, classes and their methods, constructors, getters, setters and fields are [declarations](../../GLOSSARY.md#declaration).
- **R4** A `var`, `let` or `const` declaration at file level or directly in a namespace is a declaration for each name it declares, and a name whose value is an arrow function or a function expression is a function.
- **R5** TypeScript interfaces and their properties and methods, type aliases, enums and their members, and namespaces are declarations.
- **R6** A `declare module` or `declare global` block is a declaration, and its items are its members.
- **R7** A declaration written after `export` or `export default` is a declaration with `export` in its range, and an anonymous default export of a function or class is named `default`.
- **R8** Imports, re-exports, `export default` of an expression, destructuring declarations, object literal members, CommonJS `exports` assignments and anything inside a function body are not declarations.
- **R9** A declaration's range includes its decorators and its JSDoc comment.
- **R10** A function's or method's [signature](../../GLOSSARY.md#signature) in TypeScript is its parameter types, with `?` for an optional and `...` for a rest parameter, and a parameter without a type counts as `any`.
- **R11** A function's or method's signature in JavaScript is its parameter names, with `...` for a rest parameter, without default values.
- **R12** TypeScript overload signatures and their implementation are separate declarations.
- **R13** Whitespace and line breaks outside string literals and JSX text are formatting-only when nothing else changed, except where R14 and R15 apply.
- **R14** A whitespace change at a point where a statement ends without a semicolon, or right after `return`, `throw`, `break`, `continue` or `yield`, is a change.
- **R15** A whitespace change in JSX text between tags is a change.
- **R16** Statements moved inside and between declarations are found as [moved code](../relationships/08-moves-inside-declarations.md), as in the other languages.
- **R17** Extraction detection, inlining detection, rename candidates and kind changes work for JavaScript and TypeScript as for the other languages.
- **R18** The merge outline and the Changes tree show JavaScript and TypeScript files as they show Java files.
- **R19** JavaScript and TypeScript have golden fixtures and merge fixtures, as the other languages do.

## Out of scope

- Vue, Svelte and Astro single-file components, and JavaScript inside HTML.
- Flow type annotations; a Flow file is read as JavaScript.
- Members of object literals, such as Vue options objects or `module.exports = { … }`.
- Grouping renames with their usages; a JavaScript reference resolver is its own change to [relationships/04](../relationships/04-rename-grouping.md).
- Opt-in equivalence rules for JavaScript.
- Relationship benchmark fixtures and a corpus validation, which follow as their own phase.

## References

- [Details](16-javascript-typescript-details.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0011](../../adr/0011-rust-provider-build-target-spike.md), amended for paid IDEs
- [Format coverage plan: B0 and B1](../../FORMAT-COVERAGE.md#5-phases)
- [Roadmap: More languages](../../ROADMAP.md#more-languages)
