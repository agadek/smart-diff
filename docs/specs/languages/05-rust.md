# Rust

Status: proposed, 2026-11-13

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports Rust files, through the JetBrains Rust plugin's parser, when the IDE has the Rust plugin.

**Use case:** A developer reviews a Rust change in RustRover, or in another JetBrains IDE with the Rust plugin, and sees which functions, types, trait implementations and modules changed, without reading every changed line.

## Requirements

- **R1** Rust support works when the IDE has the Rust plugin, and the plugin loads without it.
- **R2** `.rs` files are a [supported language](../../GLOSSARY.md#supported-language) when the Rust plugin is present.
- **R3** Functions, structs, unions, enums, enum variants, traits, type aliases, constants, statics and `macro_rules!` macros are [declarations](../../GLOSSARY.md#declaration).
- **R4** A module, inline or declared as `mod name;`, is a declaration, and the items of an inline module are its members.
- **R5** An `impl` block is a declaration named by its type, or by `Trait for Type`, and its items are its members.
- **R6** A trait's items and an `impl` block's items, functions, types and constants, are declarations.
- **R7** A struct's named fields are declarations; tuple struct fields and the fields of an enum variant are part of their struct or variant.
- **R8** The items of an `extern` block are declarations of the container that holds the block.
- **R9** Items inside a function body, closures, `use` declarations, `extern crate`, macro invocations and items named `_` are not declarations.
- **R10** A declaration's range includes its attributes and its outer doc comments.
- **R11** A function's [signature](../../GLOSSARY.md#signature) is its `self` parameter, if any, then its parameter types, without names, patterns, generics and the return type.
- **R12** Two `impl` blocks with the same name in one container are separate declarations.
- **R13** Whitespace and line breaks outside macro invocations and string literals are [formatting-only](../../GLOSSARY.md#formatting-only) when nothing else changed.
- **R14** A whitespace change inside a macro invocation's arguments is a change.
- **R15** Extraction detection, inlining detection, rename candidates and kind changes work for Rust as for the other languages.
- **R16** The merge outline and the Changes tree show Rust files as they show Java files.
- **R17** Rust has golden fixtures, merge fixtures and relationship benchmark fixtures, as the other languages do.

## Out of scope

- Expanding macros, or declarations that only a macro generates.
- Grouping renames with their usages, until a spike shows the Rust plugin resolves references in a file without a Cargo project.
- Items gated by `#[cfg]` being told apart by their configuration; they repeat a key.

## References

- [Details](05-rust-details.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Roadmap: Rust](../../ROADMAP.md#rust)
