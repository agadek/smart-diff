# SMART Diff Validation

Results of running the declaration engine on real commits before a release, and what they changed. Rerun with
`CorpusValidationTest` (technical specification §12):

```shell
./gradlew test --tests '*CorpusValidationTest*' -Psmartdiff.validation.corpus=<list>
```

Each line of the list is a local Git repository followed by `git rev-list` arguments, for example
`/src/okhttp --no-merges --max-count=40 HEAD`. The report is written to `build/reports/smartdiff-validation/`.

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
