# Format Coverage Plan

Status: proposal, 2026-11-13, with open question F1 decided the same day (§6); G0/G1 and D0/D1 implemented 2026-11-13; D2 (YAML) implemented 2026-11-14. It expands the roadmap's [more languages](ROADMAP.md#more-languages) entry into a programme: which languages and file formats of the JetBrains platform SMART Diff covers, what each one needs, and in which order. Section numbers (§) refer to the [technical specification](TECHNICAL-SPECIFICATION.md), and terms follow the [glossary](GLOSSARY.md). [Python](specs/languages/04-python.md) and [Rust](specs/languages/05-rust.md) are specified separately and are not repeated here; this plan builds on their pattern and their build-target decision (spike S8). Each phase below gets its own feature spec and details file, written as the [specification guide](SPEC-GUIDE.md) asks, in the first pull request of that phase.

## 1. Problem

The declaration engine supports Java, Kotlin and Scala, with Python and Rust specified. Every other file in a review opens in the plain native diff, which is most of the files in a typical web, Go, PHP or Ruby project, and the configuration files of every project: JSON, YAML, XML, properties, TOML. The 2026-11-13 review named the narrow language scope as the plugin's largest weakness, and SemanticDiff supports 14 or more languages and formats ([research](RESEARCH.md#semanticdiff)).

The languages differ in what they need, so this plan groups them by need rather than by popularity:

- **(a)** Code and markup languages whose PSI ships free with IntelliJ IDEA, so they build and test on the current target.
- **(b)** Languages whose plugin is paid, closed source, or tied to another IDE, so they need another build target, as Rust does.
- **(c)** Structured data formats, which have keys and values instead of declarations, and need a model decision first ([ADR 0009](adr/0009-structured-data-keys-are-declarations.md)).
- **(d)** A generic provider for any language with a structure view, as a cheap way to reach every other language ([ADR 0010](adr/0010-structure-view-provider-for-languages-without-their-own.md)).
- **(e)** Languages that cannot be supported through PSI, and why.

## 2. Inventory

Plugin ids and licensing are as known on 2026-11-13. The first phase of each group re-checks them on IntelliJ Platform 2025.1 and the latest release, since JetBrains has moved several of these plugins in recent releases. "Bundled" means bundled with IntelliJ IDEA; since 2025.3, IntelliJ IDEA is one product whose free tier includes the open-source plugins, while Ultimate features need a subscription.

### 2.1 Group (a): free PSI on the current build target

| Format | Plugin (dependency) | Licence and source | Build and verifier target | Declarations |
| --- | --- | --- | --- | --- |
| Groovy, Gradle build scripts | `org.intellij.groovy`, bundled | Free, open source | Current target; verifier as today | Classes, interfaces, traits, enums, methods, fields, as Java; in `.gradle` scripts, the top-level blocks (`plugins`, `dependencies`, `android`, each task) as sections |
| Kotlin build scripts (`.gradle.kts`) | `org.jetbrains.kotlin`, bundled | Free, open source | Current target | Already parsed by the Kotlin provider; the top-level calls with a lambda become sections, as for Groovy scripts |
| Markdown | `org.intellij.plugins.markdown`, bundled | Free, open source | Current target | Headings as sections, nested by level; front matter as a section |
| Shell scripts | `com.jetbrains.sh`, bundled | Free, open source | Current target | Functions; the rest of the script is the file region |
| HTML | Platform XML support (`com.intellij.modules.xml`) | Free, open source | Current target | Elements with an `id`, and the `head`, `body`, `template`, `script` and `style` elements, as tags; embedded JavaScript and CSS stay content of their tag |

### 2.2 Group (b): paid, closed or tied to another IDE

| Language | Plugin (dependency) | Where it runs | Licence and source | Build and verifier target | Declarations |
| --- | --- | --- | --- | --- | --- |
| JavaScript, TypeScript, JSX, TSX | `JavaScript` | WebStorm, IntelliJ IDEA Ultimate, PhpStorm, PyCharm, GoLand, RubyMine, Rider, CLion | Closed source; WebStorm is free for noncommercial use, the others need a subscription | A WebStorm submodule or the Ultimate target, as S8 decides for Rust; verifier on WebStorm 2025.1 and latest | Functions, classes, methods, fields, interfaces, type aliases, enums, namespaces, `export`ed and top-level `const`/`let` |
| Go | `org.jetbrains.plugins.go` | GoLand, IntelliJ IDEA Ultimate | Closed source; paid | A GoLand submodule; verifier on GoLand | Functions, methods named `(T) Name`, types, struct fields, interface methods, top-level `const` and `var` |
| PHP | `com.jetbrains.php` | PhpStorm, IntelliJ IDEA Ultimate | Closed source; paid | A PhpStorm submodule; verifier on PhpStorm | Classes, interfaces, traits, enums, functions, methods, properties, constants |
| Ruby | `org.jetbrains.plugins.ruby` | RubyMine, IntelliJ IDEA Ultimate | Closed source; RubyMine's licensing to be confirmed | A RubyMine submodule; verifier on RubyMine | Modules, classes, methods, singleton methods, constants; `attr_*` and DSL calls stay content |
| SQL | `com.intellij.database` | DataGrip, IntelliJ IDEA (SQL is in the free tier from 2025.3), the other paid IDEs | Closed source | IntelliJ IDEA 2025.3 or a DataGrip submodule; the 2025.1.3 Community target has no SQL plugin | `CREATE` statements named by object (`table orders`, `view`, `function`, `procedure`, `index`); each table column as a member; other statements stay content of the file region |
| CSS, SCSS, Less | `com.intellij.css` and its dialect plugins | WebStorm, IntelliJ IDEA Ultimate and the other paid IDEs | Closed source; paid outside WebStorm | Same target as JavaScript | Rule sets named by selector, at-rules (`@media`, `@supports`, `@layer`) as containers, custom properties at top level, mixins and functions in SCSS and Less |
| Python, Rust | Specified in [languages/04](specs/languages/04-python.md) and [languages/05](specs/languages/05-rust.md) | | | | |

- **One pattern for every paid IDE.** S8 decides how Rust is compiled and tested. The same choice applies to WebStorm, GoLand, PhpStorm, RubyMine and DataGrip: if S8 picks a submodule per IDE, each language above is one more submodule with its own optional descriptor; if it picks the Ultimate target, they share it. Phase B0 (§5) records this as an amendment to the S8 ADR, so the decision is made once.
- **Closed PSI.** Every plugin in this group is closed source, like the Rust plugin. Each provider keeps all calls into its plugin in one adapter class, as `RustInterop` does, and the verifier's internal-API report is reviewed for each.
- **Verifier load.** Each IDE family adds two IDE downloads to Plugin Verifier. The verifier matrix the Python and Rust phases introduce gets one job per family.

### 2.3 Group (c): structured data formats

| Format | Plugin (dependency) | Licence | Build target | Keys | Whitespace |
| --- | --- | --- | --- | --- | --- |
| JSON, JSON with comments, JSON5 | `com.intellij.modules.json`, bundled | Free, open source | Current target | Object members, nested; arrays as in [ADR 0009](adr/0009-structured-data-keys-are-declarations.md) | Insignificant outside strings |
| YAML | `org.jetbrains.plugins.yaml`, bundled | Free, open source | Current target | Mapping keys, nested; each document of a multi-document file as a section | Indentation significant; formatting-only only after a tested rule ([ADR 0003](adr/0003-conservative-classification.md)) |
| TOML | `org.toml.lang`, bundled | Free, open source | Current target | Tables and array-of-tables entries as sections, keys as members, dotted keys split | Line breaks significant |
| Properties | `com.intellij.properties`, bundled | Free, open source | Current target | Each key; no nesting | Insignificant around the separator; line continuations are layout |
| XML (Maven, Spring, Android, any) | `com.intellij.modules.xml`, platform | Free, open source | Current target | Tags, named by tag and an identifying attribute or child (§4) | Insignificant inside a tag; text content compared exactly |

- **Common noise.** Reordered keys, reformatted files and re-indented blocks are the usual noise in configuration reviews, and a renamed key is otherwise shown as a removed and an added line far apart.
- **No new engine.** ADR 0009 proposes that keys are declarations of the existing engine, so the outline, the Changes tree, the merge outline and the filter work for these files without UI changes.

### 2.4 Group (d): every other language, through the structure view

Most languages the IDE parses, including those of group (b) and third-party plugins such as Dart, Lua, Elixir, Haskell, Terraform (HCL), Protocol Buffers, Dockerfile and GraphQL, have a structure view: the tree the IDE's Structure tool window shows. [ADR 0010](adr/0010-structure-view-provider-for-languages-without-their-own.md) specifies the generic provider that builds a declaration tree from it; G0/G1 implement it ([languages/06](specs/languages/06-structure-view-fallback.md)).

- **Dependency:** none at compile time. The structure view is platform API (`LanguageStructureViewBuilder`), so the provider works for any language plugin installed in the user's IDE, paid ones included, without a build target for that IDE.
- **What it gives:** the names, nesting and ranges of the items the structure view lists, so the outline shows which of them were added, removed, changed or moved.
- **What it does not give:** signatures, a whitespace rule or reliable kinds. Overloads share a key and follow the matcher's rules for repeated keys; whitespace is `SIGNIFICANT`, so nothing is ever formatting-only; kinds are guessed from the PSI class name, or left generic.
- **Where it stops:** a dedicated provider replaces it for a language as soon as one exists, since the provider registry picks a language's own provider first.

### 2.5 Group (e): not supported, and why

| Language | Why | Route, if any |
| --- | --- | --- |
| C#, F#, VB.NET (Rider) | Rider's language support runs in the ReSharper backend process. The IntelliJ-side PSI of these files is a thin stub with no declarations, and the structure view is filled over the backend protocol, not from PSI | A [Tree-sitter provider](ROADMAP.md#tree-sitter-support), later |
| C, C++, Objective-C (CLion) | CLion Nova, the ReSharper-based engine, is the default for all users from 2025.3; the classic engine's PSI is being retired. The same backend split as Rider applies | Tree-sitter, later; or the structure view, if S9 shows CLion fills it with PSI-backed items |
| Swift | No current JetBrains IDE (AppCode was discontinued) | Tree-sitter, later |
| Jupyter notebooks | A notebook is JSON with cells; the diff the IDE shows is its own notebook diff, not the text diff the outline attaches to | Out of scope |
| Binary and generated files (images, lock files, minified bundles) | No structure worth showing, or too large for the [analysis limits](GLOSSARY.md#analysis-limits) | Native diff, as today ([product scope](specs/README.md#product-scope) P5) |

## 3. Generic fallback (group d)

The structure-view provider is the cheapest route to "every typical format", so it is designed here in more depth. Spike S9 checks it.

1. **Selection.** The provider registry asks each dedicated provider first. When none supports the file's language, and the setting *Use the IDE's structure view for other languages* is on, the generic provider handles any language that has a `TreeBasedStructureViewBuilder`. A language without one stays unsupported.
2. **Model.** `createStructureViewModel(editor = null)` on the PSI file from text (§6.2). The provider walks `getRoot().getChildren()` recursively, ignoring the model's sorters, filters and groupers, and keeps the items whose `getValue()` is a `PsiElement` in the same file. Inherited and grouped items, which point into other files or into no element, are dropped.
3. **Declarations.** Each kept item is a declaration: its name is the presentation's text, its range is the element's text range, its children are its members. Two items with one range (a property and its getter in some languages) become one declaration.
4. **Kinds.** A new kind, `ITEM`, shown without a kind word. A small table maps PSI class names that contain `Class`, `Interface`, `Function`, `Method`, `Field` or `Property` to the existing kinds; everything else is `ITEM`. Kind changes (rule M4b) apply only to mapped kinds.
5. **Tokens and whitespace.** Own tokens come from the language's lexer, as for every PSI provider (§6.3). The policy is `SIGNIFICANT`: formatting-only is never claimed, since no rule for the language has been tested ([ADR 0003](adr/0003-conservative-classification.md)).
6. **Labels.** The outline's summary line says `structure view` for these files, and the row tooltip says the outline comes from the IDE's structure view, so a reviewer knows it is coarser than a dedicated language.
7. **Failure.** A structure view that throws, needs an editor, or takes longer than the [analysis limits](GLOSSARY.md#analysis-limits) makes the file `FAILED`, and the native diff shows, with the reason.

S9 is done when the provider gives correct ranges and names on text-built files for JSON, YAML, Markdown, properties and Groovy in a platform test on the current target, and a manual run on WebStorm and GoLand shows outlines for TypeScript, CSS and Go files with no errors in the log. S9 also lists which bundled structure views need an editor or a project model and so cannot be used.

## 4. Structured data (group c)

[ADR 0009](adr/0009-structured-data-keys-are-declarations.md) has the decision; this section has the mapping each phase starts from. Spike S10 checks it.

- **Keys.** A JSON object member, a YAML mapping entry, a TOML key, a properties key and an XML tag are declarations with the new kind `KEY`, or `TAG` for XML. A TOML table, a YAML document and a Markdown heading are `SECTION`s. Their members are the keys nested in them.
- **Names.** The key as written, without quotes; the outline's tree shows the path. A properties file's dotted keys stay one key each (`spring.datasource.url`), since the format has no nesting.
- **Values.** A scalar value is the key's content: changing it changes the key.
- **Arrays.** An array is content of its key, except an array whose items are all objects or mappings with one shared identifying key (`name`, `id` or `key`, in that order): each item is then a declaration named `[name=web]`, so a reordered list of services or jobs shows as moved items instead of a changed array. Positional items are never matched by index.
- **XML names.** A tag is named by its tag name and, when present, its `id` or `name` attribute (`bean id=dataSource`). Maven POMs name `dependency` and `plugin` tags by `groupId:artifactId`, so a version bump is one changed dependency. Attributes are content of their tag.
- **Order.** Keys whose order carries no meaning (JSON objects, YAML mappings, TOML tables, properties, XML attributes) still report a reorder as moved, the existing label; the filter can hide moved changes. Array items and XML child tags keep their order as content.
- **Repeated keys.** A key that appears twice in one container, which most formats allow syntactically, follows the matcher's rules for repeated keys.
- **Relationships.** Rename candidates apply: a key renamed with its value unchanged pairs as a rename candidate. Extraction and inlining detection need declaration bodies and do not apply; the relationship benchmark gets rename cases only.
- **Whitespace.** As in the table in §2.3. YAML starts as `SIGNIFICANT` and moves to `INDENTATION_SIGNIFICANT` only with fixtures that show re-indenting a block is never called formatting-only when it changes nesting.

S10 is done when text-built JSON, YAML, TOML, properties and XML files give correct key ranges in a platform test on the current target, including JSON with comments, a multi-document YAML file, YAML anchors and aliases, and a TOML array of tables.

## 5. Phases

Each phase is one pull request, or a spec pull request and a code pull request, and is done when its requirements pass in tests named after them, the verifier passes on every target, and, for a language, golden fixtures, merge fixtures and a corpus validation exist as for the others (§12). Python (P0 to P2) and Rust (R0 to R2) run before or beside them.

| Phase | Content | Depends on | Status |
| --- | --- | --- | --- |
| G0 | Spike S9, ADR 0010 accepted | Nothing | Implemented 2026-11-13 |
| G1 | Structure-view provider: `languages/06`, the `ITEM` kind, the setting, the summary-line note | G0 | Implemented 2026-11-13 |
| D0 | Spike S10, ADR 0009 accepted, the `KEY`, `TAG` and `SECTION` kinds | Nothing; can run beside G0 | Not started |
| D1 | JSON provider | D0 | Not started |
| D2 | YAML provider, with the indentation rule as its own requirement | D1 | Implemented 2026-11-14 |
| D3 | Properties and TOML providers | D1 | Not started |
| D4 | XML provider, with POM and Spring naming; HTML on the same provider | D1 | Not started |
| B0 | Build pattern for paid IDEs, as an amendment to the S8 ADR; verifier jobs for WebStorm and GoLand | R0 | Not started |
| B1 | JavaScript and TypeScript provider | B0 | Not started |
| B2 | Go provider | B0 | Not started |
| M1 | Markdown provider | D0 (the `SECTION` kind) | Not started |
| M2 | Groovy provider and build-script sections, Groovy and Kotlin | Nothing | Not started |
| M3 | Shell provider | Nothing | Implemented 2026-11-14 |
| B3 | CSS, SCSS and Less provider | B1 | Not started |
| B4 | PHP provider | B0 | Not started |
| B5 | Ruby provider | B0 | Not started |
| B6 | SQL provider | B0, or a raised build target (§6) | Not started |

**Order** (decided 2026-11-13: the fallback and the dedicated providers run in parallel). After Python and Rust, two tracks start together:

- **Fallback track:** G0, then G1. It reaches every group (b) and (d) language at once with no build work, and the outlines it gives show which languages most need a dedicated provider.
- **Dedicated track:** D0, D1 and D2, then B0 and B1, B2, D3 and D4, M1 to M3, then B3 to B6 as users ask. JSON and YAML come first because they are in almost every review and need no paid IDE; TypeScript and Go have the largest audiences among the paid ones.

The two tracks share only the new kinds in `DeclarationKind` (`ITEM` from G1; `KEY`, `TAG` and `SECTION` from D0) and the provider registry's order. Whichever phase merges first adds its kinds; the other adds its own on different lines. When a dedicated provider lands for a language the fallback already covered, the provider registry picks it first, and that language's corpus run is compared with the fallback's to record what it gained.

## 6. Open questions

- **F1 Fallback first or dedicated providers first (decided 2026-11-13).** Both in parallel, as two tracks (§5).
- **F2 Fallback default.** Whether the structure-view setting is on by default. The proposal is on, with the summary-line note, since the alternative is the plain native diff and the provider never claims formatting-only.
- **F3 Build target for SQL.** SQL is in IntelliJ IDEA's free tier from 2025.3, but not in the 2025.1.3 Community target. Either SQL waits for the minimum platform to reach 2025.3, which changes P6, or it uses the paid-IDE pattern of B0.
- **F4 XML attribute order.** Attribute order carries no meaning in XML. The proposal reports a reordered attribute as a changed tag, since attributes are content; reporting it as moved needs attribute-level declarations, which would crowd the outline.
- **F5 Ruby and PHP licensing.** Whether RubyMine and PhpStorm now have a free noncommercial licence, which affects who can use those providers but not how they are built. Re-checked in B0.
