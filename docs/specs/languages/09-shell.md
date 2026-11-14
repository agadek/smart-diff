# Shell

Status: implemented, 2026-11-14

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports shell scripts, through the Shell Script plugin's parser, when the IDE has the Shell Script plugin.

**Use case:** A developer reviews a shell script change and sees which functions changed, while re-indented code and spacing between arguments stay [formatting-only](../../GLOSSARY.md#formatting-only) where the script's meaning does not depend on them.

## Requirements

- **R1** Shell support works when the IDE has the Shell Script plugin (`com.jetbrains.sh`), and SMART Diff loads without it.
- **R2** Function definitions are [declarations](../../GLOSSARY.md#declaration) of kind `FUNCTION`, in every syntax the Shell PSI accepts: `name() { ... }`, `function name { ... }`, `function name() { ... }`, with a body that is a brace block `{ ... }` or a subshell `( ... )`. A function definition written with another compound command as its body, such as `f() if ...; fi` or `f() [[ ... ]]`, is parsed as a function definition with a parse error and no block body by the Shell PSI.
- **R3** A function's name is its identity; a shell function has no declared parameters, so its [signature](../../GLOSSARY.md#signature) is empty and two definitions of the same name in one file are told apart by order of appearance.
- **R4** A function defined inside another function's body is a nested declaration of the outer function.
- **R5** A function defined inside `if`, `case`, loops or other compound commands at the top level is a top-level declaration.
- **R6** Top-level commands, variable assignments, `export`, `alias`, `source`, and the shebang line are not declarations and stay in the [file region](../../GLOSSARY.md#file-region).
- **R7** Here-documents and strings are content; whitespace inside them is [significant](../../GLOSSARY.md#significant-whitespace).
- **R8** A line break ends a command, so adding or removing a line break between tokens inside a declaration is a change; whitespace inside a line, outside quoted strings and here-documents, is formatting-only when nothing else changed.
- **R9** Joining two commands onto one line with `;` is a change.
- **R10** Comments, including the shebang, are not declarations; a comment directly above a function belongs to the function as its documentation.
- **R11** A [parse error](../../GLOSSARY.md#parse-error) in the script is reported as the Shell PSI reports it; a file with a parse error on one side falls back as for other languages.
- **R12** Shell files the IDE recognises as shell scripts, including `.sh`, `.bash` and `.zsh`, go to this provider.

## Out of scope

- Resolving function calls or `source`d files to link declarations across files.
- Treating a removed space that changes an unquoted token boundary, such as an assignment written as a command, as a change when the token kinds and texts stay the same.

## References

- [Details](09-shell-details.md)
- [Technical specification §6.12](../../TECHNICAL-SPECIFICATION.md#612-shell-provider)
- [ADR 0003](../../adr/0003-conservative-classification.md)
