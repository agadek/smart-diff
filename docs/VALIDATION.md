# SMART Diff Validation

Results of running the declaration engine on real commits before a release, and what they changed. Rerun with
`CorpusValidationTest` (technical specification §12):

```shell
./gradlew test --tests '*CorpusValidationTest*' -Psmartdiff.validation.corpus=<list>
```

Each line of the list is a local Git repository followed by `git rev-list` arguments, for example
`/src/okhttp --no-merges --max-count=40 HEAD`. The report is written to `build/reports/smartdiff-validation/`.

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
