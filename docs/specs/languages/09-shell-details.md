# Shell: details

Status: implemented, 2026-11-14

**Feature:** [Shell](09-shell.md)

## 1. Packaging and IDE versions

- **Plugin:** the Shell PSI comes from the bundled Shell Script plugin, `com.jetbrains.sh`. The build compiles against it with `bundledPlugin("com.jetbrains.sh")`. The Shell Script plugin is bundled with IntelliJ IDEA Community 2025.1.3 and with PyCharm and RustRover, the verifier targets, so it is available in every verifier run without a Marketplace install.
- **Descriptor:** the provider registers in `META-INF/smartdiff-shell.xml`, loaded through `<depends optional="true" config-file="smartdiff-shell.xml">com.jetbrains.sh</depends>` in `plugin.xml`, so SMART Diff loads when the Shell Script plugin is absent (R1).
- **Language id:** `Shell Script` (`com.intellij.sh.ShLanguage`). `ShFileType` and shebang-based file type detection map `.sh`, `.bash`, `.zsh` and extensionless files the IDE recognises as shell scripts to this language (R12).

## 2. Parsing

- `PsiFileFactory.createFileFromText(name, ShLanguage.INSTANCE, text, false, false)`, inside the engine's read action, as in technical specification §6.2. Files are created with the `.sh` extension so the Shell parser is used.
- **Dialect:** shell has one dialect for the engine; `dialect()` returns null.
- **Offsets:** PSI element ranges are offsets into the request text. Request texts are LF document snapshots (§6.2 of the technical specification).

## 3. Declarations

| PSI element | Kind | Name | Signature |
| --- | --- | --- | --- |
| `ShFunctionDefinition` (`com.intellij.sh.psi`) | FUNCTION | the function's name (`PsiNameIdentifierOwner`) | — (empty) |

- **Bodies (R2):** the body is `ShFunctionDefinition.getBlock()` (a brace block `{ ... }`) or, if null, `getParenthesesBlock()` (a subshell `( ... )`); otherwise null. `PsiDeclarationProvider` takes the inside of a `{ ... }` block as the body range; a subshell body is kept as the whole body element. The Shell PSI on 2025.1.3 parses `f() if true; then echo; fi` and `f() [[ -n x ]]` as a `ShFunctionDefinition` with a parse error and neither a block nor a parentheses block.
- **Containers:** a function is a container for function definitions nested inside its compound command body (R4). Nesting follows PSI containment: a `ShFunctionDefinition` whose ancestor chain reaches another `ShFunctionDefinition` before the file is a child of that function.
- **Top-level placement (R5):** a `ShFunctionDefinition` not nested inside another function is a top-level declaration, even when it appears inside `if`, `case`, `for`, `while`, `until` or other compound commands, because the Shell PSI places such definitions in the file's command structure rather than inside another function body.
- **Duplicates (R3):** redefining a function is legal shell. Two definitions with the same name and empty signature repeat a key and follow the matcher's rules for repeated keys: equal bodies pair in source order (M1), and the rest pair by similarity or become uncertain as for other languages.
- **Not declarations (R6):** commands, pipelines, assignments, `export`, `alias`, `source`/`.` and the shebang are not declarations. Their source belongs to the file region or to the declaration that contains them.
- **Ranges and comments (R10):** a declaration's range includes the function element (keyword, name, parentheses when present and body). A comment directly above the function with no blank line between is included in the declaration range, as Javadoc/KDoc sit inside the Java/Kotlin declaration element, so it moves with the function. The provider does not attach a comment above an enclosing compound command (such as `if`) to a function inside it, and a nested function's doc comment is never extended before the start of its containing function's body.

## 4. Tokens and whitespace

- Tokens come from the Shell lexer through `LanguageParserDefinitions` for `ShLanguage`, as for every PSI provider (§6.3).
- **Policy (R8, R9):** `SEPARATION_SIGNIFICANT`. A line break ends a command and whitespace separates tokens in shell, so a run of whitespace that contains a line break is kept as a line break and a run without one is kept as a single separator (technical specification §8). Re-indenting lines or changing the amount of space between separated tokens is therefore formatting-only when the token texts and their separation are unchanged, while adding or removing a line break, joining commands with `;`, or removing or adding separation between two tokens changes the result.
- **Strings and here-documents (R7):** their text is token content compared exactly, so whitespace inside them is never formatting-only.

## 5. Parse errors

- A `PsiErrorElement` inside a declaration's own range sets `hasParseError`; the declaration stays matched by identity when its name was parsed, and its content follows the usual rule (§6.7): `UNANALYZED` unless its own tokens and signature are unchanged (R11).
- If either side has errors and no declarations were found while the file is non-blank, the engine returns `FAILED` and the native diff shows.

## 6. Relationships

- Rename candidates (M5), moves, extractions and inlinings work on the declaration model and need no shell-specific code (R3 gives an empty signature, so functions pair by name and content as other declarations do).
- No shell reference resolver is registered. Resolving function calls and `source`d files for rename grouping is left as an open question; usages are not grouped.

## 7. Tests

- **`ShellDeclarationProviderTest`** (`BasePlatformTestCase`): each test names its requirement, covering R2 to R12 and parse errors (function syntaxes, empty signature, duplicates, nested functions, functions inside `if`, top-level commands in the file region, comment attachment, whitespace policy and parse errors).
- **Golden fixtures** in `src/test/testData/shell/<case>/` with `old.sh`/`new.sh`, run by `GoldenFixturesTest`: function added, removed, body changed, moved, renamed, each definition syntax, a redefinition (duplicate name), a nested function, a function inside `if`, top-level commands only, heredoc change, re-indent (formatting-only), argument spacing (formatting-only), `a=1` vs `a = 1` (limitation), commands joined with `;` (change), shebang change, parse error on one side, identical, empty.
- **Merge fixtures** in `src/test/testData/shell/merge/<case>/`, covering the standard required merge scenarios.
- **Corpus validation:** `CorpusValidationTest` accepts `.sh` files when a shell corpus is listed; no shell corpus is bundled, so running it needs a corpus that is not available offline.

## 8. Open questions

- **Reference resolution:** resolving function calls by name and `source`d files to group renamed functions with their usages, as the Java, Kotlin and Scala resolvers do.
- **Dialect and compatibility:** whether the bundled Shell Script plugin is present and behaves the same in every verifier target IDE; the 2025.1.3 targets bundle it, but later targets should be confirmed by the verifier.
