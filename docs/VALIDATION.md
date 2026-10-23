# SMART Diff Validation

Results of running the declaration engine on real commits before a release, and what they changed. Rerun with
`CorpusValidationTest` (technical specification §12):

```shell
./gradlew test --tests '*CorpusValidationTest*' -Psmartdiff.validation.corpus=<list>
```

Each line of the list is a local Git repository followed by `git rev-list` arguments, for example
`/src/okhttp --no-merges --max-count=40 HEAD`. The report is written to `build/reports/smartdiff-validation/`.

## 2026-10-22: Scala

**Corpus:** the last non-merge commits touching Scala in three projects, with the Scala plugin 2025.1.32:
typelevel/cats (Scala 2 syntax, 40 commits), zio/zio (cross-built for Scala 2 and 3, 80 commits) and scala/scala3
(the compiler, written in Scala 3 with indentation syntax, and its tests, 40 commits). 145 of the 160 commits modified
a Scala file: 596 files (cats 307, zio 118, scala3 171). The summary now records the dialect each file was parsed in.

**Overall:** 536 files were `ANALYZED` and 60 `PARTIAL`; none fell back to the native diff. The median analysis time
was 19 ms, against 4 ms for Java and Kotlin, since the dialect is chosen by parsing both sides first; the slowest file
took 542 ms (zio `ZStream.scala`, 6300 lines). No file broke the nothing-hidden rule and none had a difference with
nothing reported. Every file, cats and zio included, took the Scala 3 dialect: the Scala 3 parser accepts their Scala 2
syntax, and no file used a construct Scala 3 dropped.

79 files were flagged for review:

| Flag | Files |
| --- | --- |
| `status` (`PARTIAL`) | 60 |
| `noisy` | 15, 8 of them `PARTIAL` |
| `rename` | 10 |
| `rename-candidate` | 4 |
| `same-name` | 1 |

### Wrong: Scaladoc markup counted as a parse error

50 of the 60 `PARTIAL` files (cats 18, zio 2, scala3 30) parse without a single error in code. Their errors are the
Scaladoc parser's: wiki links that close before they open (`[[F[A]]]` in prose, `` `[[Ior.Left Left]][A]` ``), links
that cross tags, and an unbalanced `{{{ … }}}` code block. The provider counts every `PsiErrorElement`, so each declaration whose doc
comment has one is `UNANALYZED`, changed or not: cats `Alternative.scala` shows the untouched `separateFoldable` as
unanalyzed, and the 177-line `package object cats` is unanalyzed for a two-line change to one of its properties. The
same errors are counted for both dialects, so they never changed the dialect.

### Wrong: valid Scala 3 the Scala plugin cannot parse

The other 10 `PARTIAL` files are scala3 compiler sources that compile, but where the Scala plugin's parser reports
errors: `this.match` used as a selector, a leading infix operator (`|| sym.owner == …`) continuing a condition on the
next line, `for (orig, forwarded) <- …` with a tuple pattern, `then""` without a space, `.tpe: @unchecked match`, a
trailing comma after a definition, and an interpolated string ending in `}$debugInfo"`. The provider cannot do better
than the PSI, but each such error makes its whole declaration unanalyzed: `Scanners.scala` shows the 1445-line class
`Scanner` as unanalyzed for a one-line change elsewhere.

Across all 60 files, 163 declaration rows are unanalyzed, many of them unchanged on both sides.

### Noisy: a trait turned into a class

cats "Reduce jar size by using abstract classes" changes `private trait RepresentableMonad` into `abstract private
class RepresentableMonad`, and the same in `Func.scala` and `IndexedReaderWriterStateT.scala`. A kind change is a
removed and an added declaration (§6.8), so each one-line change shows the container removed, the new one added and
every member as moved; in `IndexedReaderWriterStateT.scala` the unchanged `implicit def F` even shows as removed and
added, since other containers in the file have members named `F` too.

### Checked against `git show`

22 flagged files across all three repositories:

| File | Outline | Verdict |
| --- | --- | --- |
| cats `Alternative.scala` | unchanged `separateFoldable` unanalyzed | wrong: Scaladoc |
| cats `package.scala` | `package object cats` unanalyzed | wrong: Scaladoc |
| zio `ZStream.scala` | unchanged declarations unanalyzed | wrong: Scaladoc |
| scala3 `Parsers.scala` | `object trim` and its members unanalyzed | wrong: Scaladoc |
| scala3 `SymDenotations.scala` | class `ClassDenotation` unanalyzed | wrong: parser (leading `\|\|`) |
| scala3 `Namer.scala` | declarations unanalyzed | wrong: parser (`for (a, b) <-`) |
| cats `Representable.scala` | traits removed, classes added, members moved | noisy: kind change |
| cats `Func.scala` | `AppFuncApplicative` removed and added | noisy: kind change |
| cats `IndexedReaderWriterStateT.scala` | `IRWSTFunctor` removed and added, `F` removed and added | noisy: kind change |
| cats `FlatMap.scala` | `AbstractFoldableFlatMap` rename candidate from `FoldableFlatMap` | right (besides one Scaladoc row) |
| cats `NonEmptyReducible.scala` | members moved into the new `NonEmptyReducibleTrait` | right |
| cats `NonEmptyList.scala` | a doc example's spacing shows as changed | right: comment text |
| zio `FiberRefs.scala` | `needsTransformWhenForked` removed, `needTransformWhenForked` added | judgement call: renamed and rewritten |
| zio `CyclicBarrier.scala` | most members removed and added | right: rewritten |
| zio `showcase.scala` | declarations removed and added | right: rewritten |
| scala3 `AggregateClassPath.scala` | `searchLocations` rename candidate from `asURLs` | judgement call: one method replaced another |
| scala3 `ClassPath.scala` and four siblings | `asURLs` removed, `searchLocations` added | right |
| scala3 `FileWriters.scala` | `openOptions` rename candidate from `fastOpenOptions` | right |
| scala3 `ScaladocConfigs0.scala` | `def dottyExternalMapping` removed, `val dottyExternalMapping` added | judgement call: kind change |
| scala3 `ScaladocGeneration.scala` | 219 lines of declarations removed | right |
| scala3 `api.scala` | new enum, class and object added | right |
| scala3 `CustomReflectSelectableTestScala3.scala` | tests added | right |

### Whitespace

34 files had whitespace-only changes. 31 came out formatting-only, all in one cats reformatting commit; the other 3
changed whitespace inside comments, which is a comment change, as for Java and Kotlin. Since every file took Scala 3,
Scala 2 code also runs under `INDENTATION_SIGNIFICANT`, so re-indenting it would be a content change; no commit in the
corpus did that.

### What to fix

1. Ignore parse errors inside Scaladoc comments, both for `hasParseError` and in the dialect choice; Scaladoc markup
   is not code. This removes 50 of the 60 `PARTIAL` results.
2. Treat a declaration whose own tokens are identical on both sides as `UNCHANGED` even when it has a parse error,
   so a parser gap only marks code that changed (§6.7). This removes the unanalyzed rows for unchanged declarations.
3. Pair a trait and a class (and a `def` and a `val`) with the same name in the same container as a kind change of
   one declaration, rather than a removed and an added one, so their members match in place.
4. Consider choosing Scala 2 for code that parses in both dialects but has no Scala 3 syntax, so re-indenting Scala 2
   code stays formatting-only.
5. Recheck the 10 parser-gap files with newer Scala plugin releases; report the constructs to the Scala plugin if
   they still fail.

**After fixes 1, 2 and 4** (Scaladoc errors ignored, identical declarations unchanged despite parse errors, Scala 2 for
code without Scala 3 syntax), on the same corpus: 595 files `ANALYZED` and 1 `PARTIAL` (scala3 `Implicits.scala`,
where the changed declaration itself has a parser gap), against 536 and 60; 1 unanalyzed row, against 163. All 307
cats files now take Scala 2, zio 109 of 118 (the other 9 are in `scala-3/` source directories or use top-level
definitions) and scala3 17 of 171, 433 in all. 21 files are flagged, against 79: `rename` 10, `noisy` 7,
`rename-candidate` 4, `same-name` 1, `status` 1; the trait-to-class files remain. The median time was 23 ms and the
slowest file took 619 ms (zio `ZStream.scala`), since files without Scala 3 syntax are now parsed in both dialects.

## 2026-10-22: 0.1.1 noise pass

**Corpus:** the same 234 commits and 1279 files as before 0.1.0, from the same clones.

**Overall:** every file was `ANALYZED`. The median analysis time was 4 ms and the slowest file took 158 ms (okhttp
`CallTest.kt`). The nothing-hidden check is now exact and about linear for local changes, so it ran on all 1279 files
(865 before): none broke the rule and none had a difference with nothing reported.

84 files were flagged for review, against 100 before 0.1.0:

| Flag | Before 0.1.0 | 0.1.1 |
| --- | --- | --- |
| `rename` (removed + added that look like a rename) | 70 | 21 |
| `uncertain` | 7 overload sets, plus the M4 cases fixed for 0.1.0 | 1 |
| `rename-candidate` (new) | | 55 |
| `noisy` | 16 | 12 |
| `same-name` | 1 | 1 |

### Rename candidates

55 files have rename candidates. Of the 15 checked against `git show` across all six repositories, 14 were right and
one was a judgement call: moshi `AdapterGenerator` split `generateToJsonFun` into `generateToJson` and
`generateToJsonRegular`, and the candidate follows the bulk of the body to `generateToJsonRegular`. The guava
`entryIterator` → `internalEntryIterator` commit and the spring-petclinic `testFoo()` → `foo()` commit now read as
renames. Sibling tests with the same body are told apart by their names; one petclinic test,
`testProcessCreationFormWithBlankName`, still is not, because its siblings' names are about as close.

The 21 files still flagged `rename` are mostly real rewrites (okhttp `EchTest.kt`, commons-lang
`LockingVisitorsTest`), declarations too small to compare by content (guava `abstract int distinctElements();`, the
empty `reset()` → `clear()` of the fixtures), renames with heavily changed bodies (guava `TempFileCreator`
`createTempFile` overrides) and one-to-two splits the margin leaves alone (Exposed
`minimumPluginConfigurationsForDatabaseApplied` → `…Applied01` and `…Applied02`).

### Overloads

The commons-lang `TypeUtils`, moshi `JsonAdapter` and Exposed `Queries.kt` overload sets now pair as signature
changes. The one file still uncertain is moshi `JvmDescriptors.kt`, where one extension property,
`Executable.jvmMethodSignature`, became two, `Constructor<*>.jvmMethodSignature` and `Method.jvmMethodSignature`: a
real ambiguity.

### Labels

Labels keep a space between words (`Class<out Annotation>`, `vararg Class<out Annotation>`) and show a Kotlin receiver
before the name (`Cart.isFull()`).

## 2026-10-22: before 0.1.0

**Corpus:** the last 40 non-merge commits touching Java or Kotlin in square/okhttp, google/guava, apache/commons-lang,
JetBrains/Exposed, square/moshi and spring-projects/spring-petclinic: 234 commits, 1279 modified files.

**Overall:** every file was `ANALYZED`; no file fell back to the native diff. The median analysis time was 5 ms and the
slowest file took 179 ms (okhttp `CallTest.kt`), far inside the 5 s timeout. No checked file broke the nothing-hidden
rule and none had a difference with nothing reported. The nothing-hidden check aligns the texts character by
character, so it ran only on files where the product of the two lengths is at most 4 × 10^8: 865 of the 1279 files.
The 414 larger files were analyzed but not checked.

100 files were flagged for review. Grouped by cause:

### Wrong: unchanged declarations reported as uncertain

Rule M4 makes every declaration whose (kind, path, name) key occurs more than once on a side uncertain, even when
the declarations are identical on both sides. The outline then shows untouched code as uncertain, often with a dozen
candidates each.

| Case | Example |
| --- | --- |
| Several unnamed declarations of one kind in a container | commons-lang `ClassUtils`: four unchanged static initializers, uncertain in both commits that touched the file |
| Equal signature keys for different declarations | guava `ImmutableSortedMultiset`: twelve `of(…)` overloads appear twice (the deprecated copies have the same parameter types), 24 uncertain nodes for a change that only adds a `serialVersionUID` field |
| Kotlin `vararg` dropped from the signature (§6.6) | moshi `Moshi.kt`: `adapter(Type, Class<out Annotation>)` and `adapter(Type, vararg Class<out Annotation>)` share the key `Type,Class<outAnnotation>`, so they are uncertain in all 7 commits that touched the file, including one that only changed imports |
| Same-signature members in one container | Exposed `References.kt`: two unchanged `orderBy(Pair<Expression<*>,SortOrder>)` |

Proposed fix, before the release: pair same-key declarations whose text is identical, then pair the rest of a
same-key group in source order when both sides have the same number of them; keep `...` for a Kotlin `vararg`
parameter, as for Java.

### Noisy: overload sets whose signatures all changed

When every overload of a name changes signature at once, rule M3 cannot pair them, so all of them are uncertain
with each other as candidates. Examples: commons-lang `TypeUtils` (each `isAssignable(…, Map)` gained a `boolean`
parameter), moshi `JsonAdapter` (`T?` → `T` on three `toJson` overloads), Exposed `Queries.kt` (`batchInsert` gained
a parameter). This is the conservative behaviour ADR 0003 asks for, but a pairing by parameter-list similarity and
position would resolve most of them.

### Noisy: renames reported literally

70 files: a renamed method, field or class is reported as removed + added, as the first release intends (Q2). Most are
at the same lines with the same or nearly the same body, for example a guava commit renaming `entryIterator` and
`distinctElements` across 13 files and a spring-petclinic commit renaming test methods `testFoo()` to `foo()` in 10
files. This is the strongest case for [rename candidates](ROADMAP.md#rename-candidates).

### Noisy: extractions

moshi `AdapterGenerator`: the body of `generateFromJsonFun` moved into a new `generateFromJsonRegular`, shown as a
changed shell plus an added method, as [extraction detection](ROADMAP.md#extraction-detection) describes.

### Labels hard to read

Removing all whitespace from signatures (§6.6) also removes it from the labels: `Class<outAnnotation>`,
`Multiset<?extendsE>`. A Kotlin extension function without parameters reads `formatIsoDate(Date.)`. Proposed fix:
collapse whitespace to one space where it separates words, and show the receiver before the name
(`Date.formatIsoDate()`).

### Not a problem

- `noisy` (16 files): every case was a large addition of new tests or classes, correctly reported as added.
- `same-name` (1 file): okhttp `FakeSocket.kt`, where `close()` was removed from two classes and added to a new one;
  correct.
