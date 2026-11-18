---
status: accepted
---

# Tree-sitter runs in a native helper process

The [Tree-sitter engine](../specs/engines/05-tree-sitter.md) needs a way to run Tree-sitter grammars from the plugin. It must work on JetBrains Runtime 21 (the build target, IntelliJ IDEA Community 2025.1.3) on macOS, Linux and Windows on x64 and arm64 (R22), it must never end the IDE when a grammar fails (R24), it must fit the 5 s [time limit](../specs/engines/03-analysis-limits.md) for two 1 MB sides, and it must add at most 25 MB to the plugin archive (R28). Spike S12 ([details §2](../specs/engines/05-tree-sitter-details.md#2-spike-s12)) measured the four options on 2026-11-17 on an Apple M-series Mac (macOS, arm64) with JDK 21. Every grammar it used is MIT-licensed, and so is the Tree-sitter runtime.

## Decision

Tree-sitter runs in **one native helper executable per platform**, `smartdiff-treesitter`, built from pinned sources: the Tree-sitter runtime 0.25.3 and the Swift (0.7.4), C# (0.23.1), C (0.23.2) and C++ (0.23.4) grammars, all linked into the one executable. The IDE starts it on the first comparison that needs it and talks to it over standard input and output:

- A request is one byte for the language, a 32-bit little-endian byte length, and the text as UTF-16LE, so node offsets come back in the request text's own `char` units.
- The response is a 32-bit node count, then six 32-bit integers per node in pre-order: the grammar symbol, the start and end offsets, the parent's index, the field id and flags (named, error, missing). The engine asks the helper for each grammar's symbol and field names once, with a request whose language byte has its high bit set (`treesitter-helper/main.c` documents the protocol).
- The helper serves requests one after another until its input closes. Each comparison holds the helper for both sides, and the engine runs one helper per IDE session.
- When the helper dies or answers garbage, the comparison is `FAILED` (R24), and the next comparison starts a new helper. When it cannot be started at all, the engine turns itself off through the API guard (R23).

The executables ship in the plugin archive and are copied to the IDE's system directory, under a folder named by the engine version, then marked executable before the first start. Nothing is downloaded (R26).

## Findings

| Option | What S12 found |
| --- | --- |
| **A, JNI binding** (`io.github.bonede:tree-sitter` 0.25.3 with its grammar artifacts) | All four samples parse without errors, and the node types match [details §5](../specs/engines/05-tree-sitter-details.md#5-language-tables). It ships natives for macOS arm64 and x64, Linux x64 and arm64, and Windows x64, but **not Windows arm64** (R22). A 1 MB side parses in 0.4 to 1.2 s, and walking its 350,000 to 435,000 nodes through JNI calls takes 1.6 to 1.9 s with a tree cursor, so a 1 MB Swift diff needs about 6 s (over the time limit). Its native code runs in the IDE process, so a fault in a grammar ends the IDE (R24). The jars add about 6.5 MB. |
| **B, WebAssembly on Chicory** (Chicory 1.7.5, grammars built with wasi-sdk 34) | The three grammars built to WebAssembly modules of 3.7 to 6.2 MB, about 0.4 MB each compressed, and parse correctly with no native code. But Chicory runs `ts_lex`, the generated lexer, in its interpreter because the function is too large for one JVM method, even with its compiler on. A 200 KB C++ side took 7 s and a 200 KB Swift side 21 s, and a 1 MB Swift side took 108 s, **more than 10 times the time limit**. |
| **C, the official Java binding** (`io.github.tree-sitter:jtreesitter` 0.27.0) | Its classes are Java 23 class files (major version 67), so it **cannot load on JetBrains Runtime 21**. It also ships no native libraries, so each grammar and the runtime would still need building per platform. It becomes an option only when the plugin's lowest supported IDE runs on Java 23 or later. |
| **D, a native helper process** (the shim above, built with Apple clang on the Mac) | Both 1 MB sides take **0.3 to 0.5 s** in every language, including sending the text and reading every node back. Starting the helper and the first small parse take 0.25 s. Killing the helper with `SIGKILL` during a 4 MB parse gave the JVM an `EOFException`, and a new helper then answered normally. The stripped macOS arm64 executable with all four grammars is 14.5 MB, or 1.2 MB compressed, so six platforms add about 7.5 MB to the archive. |

S12's second question, the language ids: in the test IDE (IntelliJ IDEA Community 2025.1.3), `.swift`, `.cxx`, `.c++`, `.hh` and `.hxx` have the unknown file type, `.cs` has a `C#` file type, and `.c`, `.h`, `.cpp`, `.cc` and `.hpp` have a `C++` file type. None of these file types has a language, with or without the bundled TextMate plugin loaded. ADR 0015's resolver therefore starts from a missing language id. `TreeSitterS12SpikeTest` checks this and prints each line in the build log. Whether a running IDE with TextMate bundles enabled gives these files the TextMate language is not checked headless. The resolver accepts that id too, so either outcome resolves.

S12's third question, Rider and CLion Nova, was not run: neither IDE is installed on the spike machine. It stays a recorded manual check for the later change that decides them.

## Considered Options

- **A, JNI binding (rejected):** fast to adopt, but no Windows arm64 build, over the time limit for 1 MB Swift because of per-node JNI calls, and no crash isolation. A one-call flat-tree shim over JNI would fix the speed but not the isolation.
- **B, WebAssembly on a JVM runtime (rejected):** the safest and smallest, but Tree-sitter's generated lexer is too large for JIT compilation on Chicory, so it is far too slow. It is worth measuring again if Chicory compiles very large functions, or if grammars can be generated with a split lexer.
- **C, the official Java binding (rejected for now):** needs Java 23, and still needs native libraries.
- **D, a native helper process (chosen):** fast, isolated from the IDE process, small, and buildable for every platform of R22 from the same C sources.

## Consequences

- Product rule P1 and engine contract R10 said that no source leaves the IDE process. They now say that no source leaves the computer and that it reaches only the plugin's own helpers: contract R10 is retired for R13, and the technical specification's engine rule 7 says the same. The helper opens no file and no network connection, so the privacy that P1 protects is unchanged.
- `treesitter-helper/main.c` is the helper, and `gradle/treesitter-helper.gradle.kts` builds it (T1a).
- The build compiles the helper from pinned sources with a C compiler. The CI workflow builds it on native runners for macOS (arm64 and x64), Linux (x64 and arm64) and Windows (x64 and arm64), and the release job packages all six. A local build packages only the host platform's helper, and the engine reports its language as unsupported on any other platform.
- The engine code talks to a process, not a library, so it needs no JNI, and the Plugin Verifier sees only JVM classes.
- Each platform's helper is an executable the plugin starts. The T1 manual check confirms on macOS that an executable copied by the IDE starts without a Gatekeeper prompt, and on Windows that it starts without a SmartScreen prompt. If either prompts, signing the helpers becomes part of the release.
- Grammar node and field names come from the helper's names request, which the tree builder's tests check against [details §5](../specs/engines/05-tree-sitter-details.md#5-language-tables).
- A grammar update means rebuilding the helper and raising the engine version (R29).
