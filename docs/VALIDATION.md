# SMART Diff Validation

Classification quality beyond this release corpus is measured by the labelled [relationship benchmark](RELATIONSHIP-BENCHMARK.md); this file stays the record of the release runs. Every run below that includes Scala uses the pinned Scala plugin version (`scalaPluginVersion` in `gradle.properties`, currently 2025.1.32).

Results of running the declaration engine on real commits before a release, and what they changed. Rerun with
`CorpusValidationTest` (technical specification §12):

```shell
./gradlew test --tests '*CorpusValidationTest*' -Psmartdiff.validation.corpus=<list>
```

Each line of the list is a local Git repository followed by `git rev-list` arguments, for example
`/src/okhttp --no-merges --max-count=40 HEAD`. The report is written to `build/reports/smartdiff-validation/`.

Merge validation uses the same list format. The merge mode adds `--merges` itself, so the list arguments should not add
`--no-merges`:

```shell
./gradlew test --tests '*CorpusValidationTest*' -Psmartdiff.validation.merges=<list>
```

Its report is written to `build/reports/smartdiff-validation/merge/`.

## 2026-11-02: relationship benchmark baseline

This is the R29 baseline: the first run with no shortfall. It covers the full case matrix and 30 labelled real
commits per corpus, so it is a complete run, not the [2026-11-01 smoke run](#2026-11-01-relationship-benchmark)
below.

**Corpus:** `src/test/testData/relationships-corpus-full.txt` lists all 304 checked-in cases and the real commits.
The cases are 25 categories with 3 cases for each of Java, Kotlin, Scala 2 and Scala 3, plus the four starter cases
(300 + 4); Scala 2 and Scala 3 stay separate rows. The real-commit lines name the clones by relative path, so the list
contains no user name; on another machine the `/src/...` prefix stands for the clone location, as in the older
validations:

```text
/src/commons-lang --no-walk <17 commit hashes>
/src/guava --no-walk <3 commit hashes>
/src/moshi --no-walk <8 commit hashes>
/src/okhttp --no-walk <5 commit hashes>
/src/spring-petclinic --no-walk <9 commit hashes>
/src/Exposed --no-walk <18 commit hashes>
/src/cats --no-walk <30 commit hashes>
/src/scala3 --no-walk <30 commit hashes>
```

The hashes are pinned with `--no-walk`, so the run does not depend on each clone's current branch tip. Every selected
commit changes one Java, Kotlin or Scala file, and every one of its files has a reviewed label. The clones were at:

| Clone | HEAD |
| --- | --- |
| commons-lang | `b9b30efb01e861a16503232bb96b684107e73199` |
| guava | `b157643c41f9ee6a38732b4fa1a590ba187912d9` |
| moshi | `889013ec2edb8d8034902662a1dc8c4f3b3f8111` |
| okhttp | `2b6399483639405e9277d33a3a1d355d5fabda1c` |
| spring-petclinic | `500158f732419217507c7656904b8e6aa1bcc0d6` |
| Exposed | `09f814c61c22851b76840ccb65c1ac2c4db97452` |
| cats | `e28985fe2a0d7123a8fb84cbb70c72b5e3d935ca` |
| scala3 | `3e003afbf3854d9b3189a51a47258d0b9e31e792` |

**Versions:** engine `smartdiff.declarations` 6; IDE 2025.1.3, build `251.26927.53`; Scala plugin 2025.1.32; JVM
21.0.9; macOS 15.7.7 on arm64. Corpus revision `87588c7829c5`; gate `no-new-wrong-pairs`, with no earlier baseline
because this is the first complete run. The gate passed. The corpus revision hashes the list and every case, label and
clone HEAD it reaches, so it changes when a label changes.

**Result:** 424 cases (304 checked-in cases and 120 real commits); 30 labelled commits per corpus. No case was
unlabelled or skipped, none broke the nothing-hidden rule, 6 fell back to the native diff (the malformed pairs), and
no pair was wrong or forced. Precision is 1.000 and recall 0.826 overall. The 42 missed relationships are listed in
`report.md`; they are the known limitations below, not a shortfall.

### Per corpus

| Corpus | Cases | Precision | Recall | Wrong share | Presence | Content | Moved | Signature | Kind | Median | p95 | Slowest |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Java | 106 | 1.000 | 0.757 | 0.000 | 0.995 | 0.995 | 1.000 | 1.000 | 1.000 | 1 ms | 34 ms | 122 ms |
| Kotlin | 106 | 1.000 | 0.836 | 0.000 | 0.986 | 0.986 | 1.000 | 1.000 | 1.000 | 0 ms | 29 ms | 85 ms |
| Scala 2 | 106 | 1.000 | 0.847 | 0.000 | 1.000 | 0.986 | 0.824 | 1.000 | 1.000 | 0 ms | 55 ms | 209 ms |
| Scala 3 | 106 | 1.000 | 0.887 | 0.000 | 1.000 | 0.970 | 1.000 | 1.000 | 1.000 | 0 ms | 146 ms | 273 ms |
| tuning | 153 | 1.000 | 0.884 | 0.000 | 1.000 | 0.990 | 1.000 | 1.000 | 1.000 | 0 ms | 1 ms | 70 ms |
| held-out | 271 | 1.000 | 0.748 | 0.000 | 0.991 | 0.980 | 0.864 | 1.000 | 1.000 | 2 ms | 91 ms | 273 ms |

### Per kind

A precision or recall with nothing to divide is `n/a`, not `1.000`; it means the engine reported no pair of that kind,
not a measured perfect score.

| Corpus | Kind | TP | FP | FN | Reported | Precision | Recall |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Java | RENAME | 24 | 0 | 1 | 24 | 1.000 | 0.960 |
| Java | MOVE | 9 | 0 | 0 | 9 | 1.000 | 1.000 |
| Java | KIND_CHANGE | 3 | 0 | 0 | 3 | 1.000 | 1.000 |
| Java | EXTRACT | 20 | 0 | 14 | 20 | 1.000 | 0.588 |
| Java | INLINE | 0 | 0 | 3 | 0 | n/a | 0.000 |
| Kotlin | RENAME | 21 | 0 | 2 | 21 | 1.000 | 0.913 |
| Kotlin | MOVE | 10 | 0 | 0 | 10 | 1.000 | 1.000 |
| Kotlin | KIND_CHANGE | 3 | 0 | 0 | 3 | 1.000 | 1.000 |
| Kotlin | EXTRACT | 12 | 0 | 4 | 12 | 1.000 | 0.750 |
| Kotlin | INLINE | 0 | 0 | 3 | 0 | n/a | 0.000 |
| Scala 2 | RENAME | 23 | 0 | 0 | 23 | 1.000 | 1.000 |
| Scala 2 | MOVE | 11 | 0 | 3 | 11 | 1.000 | 0.786 |
| Scala 2 | KIND_CHANGE | 4 | 0 | 0 | 4 | 1.000 | 1.000 |
| Scala 2 | EXTRACT | 12 | 0 | 3 | 12 | 1.000 | 0.800 |
| Scala 2 | INLINE | 0 | 0 | 3 | 0 | n/a | 0.000 |
| Scala 3 | RENAME | 21 | 0 | 0 | 21 | 1.000 | 1.000 |
| Scala 3 | MOVE | 10 | 0 | 0 | 10 | 1.000 | 1.000 |
| Scala 3 | KIND_CHANGE | 4 | 0 | 0 | 4 | 1.000 | 1.000 |
| Scala 3 | EXTRACT | 12 | 0 | 3 | 12 | 1.000 | 0.800 |
| Scala 3 | INLINE | 0 | 0 | 3 | 0 | n/a | 0.000 |

**Wrong and forced pairs:** none. The adapter now names a move's old container from the old side's declaration tree,
so the Scala 2 `cats-0a776c7-applicative` move of `replicateA_` matches its label and the former cross-container move
limitation is fixed.

**Known limitations:** inlining detection is not implemented, so every INLINE label is missed and its recall starts
at zero (relationships/05). Extraction to several sources links only one source in the synthetic case (24 misses over
four corpora), and the Scala parser marks the two capture-checking real-commit files and the incomplete Scala
parse-error cases differently from the hand labels (UNANALYZED versus CHANGED); those are listed in `report.md`, not
counted as shortfalls.

**Baseline report:** `src/test/testData/relationships-baseline/report.json` is this run's `report.json`, checked in so
later gates compare against a file (relationships/01 R21, R29, R40). Its corpus revision is `87588c7829c5`.

**Report:** `build/reports/smartdiff-relationships/summary.tsv`, `report.md` and `report.json`. Run it with:

```shell
./gradlew test --tests '*RelationshipBenchmarkTest*' \
  -Psmartdiff.relationships.corpus=src/test/testData/relationships-corpus-full.txt \
  -Psmartdiff.relationships.baseline=src/test/testData/relationships-baseline/report.json
```

The [2026-11-01 smoke run](#2026-11-01-relationship-benchmark) below is kept as the harness check; it is not this
baseline.

## 2026-11-01: relationship benchmark

**Corpus:** the four starter cases in `src/test/testData/{java,kotlin,scala2,scala3}/relationships/`:
Java method rename, Kotlin method move, Scala 2 formatting-only and Scala 3 kind change. This is a **smoke run** of
the harness, not the full labelled matrix or real-commit baseline that relationships/03 needs.

The run reports `"smoke": true` because it is below the R30 minimums (3 cases per category and corpus, 30 labelled
commits per corpus). It is therefore not the R29 baseline: a later run refuses it with `baseline is a smoke run`, and
the baseline is the first run with no shortfall.

**Result:** precision 1.000 and recall 1.000 over the three relationship kinds; declaration classification
7 of 7; no forced pair, no nothing-hidden violation and no fallback. Median 23 ms, p95 and slowest 72 ms.
The gate was `no-new-wrong-pairs`; this is the first recorded run because no earlier report exists.

The report is written to `build/reports/smartdiff-relationships/`. The checked-in corpus still needed the full
category matrix and real-commit labels at the time of this run; the [2026-11-02 baseline](#2026-11-02-relationship-benchmark-baseline)
above adds them and is the R29 baseline, so relationships/01 is implemented from that run.

## 2026-11-01: merge validation

**Corpus:** merge commits from the Java and Kotlin clones and from the Scala clones added for this run, selected with
`git rev-list` arguments. The `/src/...` paths stand for the local clone location; use the clone paths on the machine
running the validation.

```text
/src/commons-lang --max-count=240 HEAD -- *.java
/src/moshi --max-count=80 HEAD -- *.java *.kt
/src/spring-petclinic --max-count=85 HEAD -- *.java
/src/guava --max-count=1 HEAD -- *.java
/src/Exposed --max-count=15 HEAD -- *.kt
/src/cats --max-count=100 HEAD -- *.scala
/src/scala3 --max-count=100 HEAD -- *.scala
```

**Result:** 275 files from 121 merge commits; 2 files were skipped because they were missing from the base or a parent,
and no merge commit had more than two parents or lacked a merge base. No file had a `join-mismatch`. The flags were
50 `both-sides-clean`, 17 `conflict-one-side` and 6 `status`; the report lists the conflicts and outline of every
flagged file.

| Language | Files | Minimum |
| --- | --- | --- |
| Java | 50 | 30 |
| Kotlin | 67 | 30 |
| Scala 2 | 117 | 30 |
| Scala 3 | 41 | 30 |

**Time:** the median was 33 ms for both comparisons together and 23 ms for one base-side comparison, so the merge
outline is within the twice-the-side limit. The two merge comparisons run in parallel, so "both comparisons together"
is wall time, not their sum.

**Ready to ship:** no `join-mismatch` and the time ratio pass. The 67 `both-sides-clean`/`conflict-one-side` files still
need review or an explanation ([review issue #54](https://github.com/agadek/smart-diff/issues/54)), so the merge outline
is not ready to ship from this run yet. The [2026-11-02 rerun](#2026-11-02-merge-validation-rerun) reviews these flags
and records the verdicts.

## 2026-11-02: merge validation rerun

The [2026-11-01 run](#2026-11-01-merge-validation) left 67 files to review. This run replays the same merge commits on the engine that will ship in 0.2.0, gives each flagged file one verdict, and records the one validation fix the review needed.

**Corpus:** the 2026-11-01 list with each clone's `HEAD` replaced by the commit below. The 2026-11-01 run did not record its clone commits, so the two commit lists may differ; the counts match exactly, and the clones had not moved between the runs. The `/src/...` paths stand for the local clones; use the clone paths on the machine running the validation.

```text
/src/commons-lang --max-count=240 b9b30efb01e861a16503232bb96b684107e73199 -- *.java
/src/moshi --max-count=80 889013ec2edb8d8034902662a1dc8c4f3b3f8111 -- *.java *.kt
/src/spring-petclinic --max-count=85 500158f732419217507c7656904b8e6aa1bcc0d6 -- *.java
/src/guava --max-count=1 b157643c41f9ee6a38732b4fa1a590ba187912d9 -- *.java
/src/Exposed --max-count=15 09f814c61c22851b76840ccb65c1ac2c4db97452 -- *.kt
/src/cats --max-count=100 e28985fe2a0d7123a8fb84cbb70c72b5e3d935ca -- *.scala
/src/scala3 --max-count=100 3e003afbf3854d9b3189a51a47258d0b9e31e792 -- *.scala
```

| Clone | Commit on 2026-11-02 |
| --- | --- |
| commons-lang | `b9b30efb01e861a16503232bb96b684107e73199` |
| moshi | `889013ec2edb8d8034902662a1dc8c4f3b3f8111` |
| spring-petclinic | `500158f732419217507c7656904b8e6aa1bcc0d6` |
| guava | `b157643c41f9ee6a38732b4fa1a590ba187912d9` |
| Exposed | `09f814c61c22851b76840ccb65c1ac2c4db97452` |
| cats | `e28985fe2a0d7123a8fb84cbb70c72b5e3d935ca` |
| scala3 | `3e003afbf3854d9b3189a51a47258d0b9e31e792` |

**Result:** 275 files from 121 merge commits, as in the first run; no merge commit had more than two parents or lacked a merge base, and 2 files were skipped for a missing side. No file had `join-mismatch` or `hidden`.

| Flag | 2026-11-01 | 2026-11-02 | Change |
| --- | --- | --- | --- |
| `both-sides-clean` | 50 | 50 | none |
| `conflict-one-side` | 17 | 12 | −5, the R29 fix below |
| `status` | 6 | 6 | none |
| `join-mismatch` | 0 | 0 | none |

The 2026-11-01 run predates the #50 fix that shipped in 0.2.0. Replaying the same commits with it changed none of these counts, so none of the flagged files was that pattern.

**R29 fix:** the first 2026-11-02 run had 17 `conflict-one-side` flags. Five of them came from a container whose own layout changed because a declaration inside it was added or removed, while the conflicting declaration under it was changed on both sides; the flag judged the container and not the declaration that holds the conflict. The check now judges the innermost declaration, and `MergeValidationTest` has a case for it. The rerun after the fix has 12.

**Time:** the median was 29 ms for both comparisons together and 23 ms for one base-side comparison, a ratio of 1.26, against 33 ms and 23 ms in the first run. Within the twice-the-side limit (R17).

**Flagged files:** 61 files. Every `both-sides-clean` and `conflict-one-side` file gets one row, and a file with both flags gets one row per flag (DMLTests).

### Expected

| File | Merge commit | Flag | Verdict | Reason |
| --- | --- | --- | --- | --- |
| `commons-lang` `src/test/java/org/apache/commons/lang3/StringEscapeUtilsTest.java` | `1a256d3e74` | `both-sides-clean` | expected | left removed blank lines inside the test, right added a CSV assertion |
| `commons-lang` `src/main/java/org/apache/commons/lang3/ClassUtils.java` | `bd7956949e` | `both-sides-clean` | expected | left renamed a caught exception, right added the method's `@throws` javadoc |
| `commons-lang` `src/main/java/org/apache/commons/lang3/reflect/MethodUtils.java` | `494dfc9e6b` | `both-sides-clean` | expected | left replaced `Validate.notNull`, right added the method's `@throws` javadoc |
| `commons-lang` `src/main/java/org/apache/commons/lang3/text/WordUtils.java` | `9604c85306` | `both-sides-clean` | expected | left removed the class javadoc's `@version` line, right added `containsAllWords` with its javadoc |
| `moshi` `moshi/src/main/java/com/squareup/moshi/ClassJsonAdapter.java` | `afb82cb3e8` | `both-sides-clean` | expected | both sides changed the `FACTORY` adapter body in different places |
| `moshi` `kotlin/codegen/src/main/java/com/squareup/moshi/kotlin/codegen/TypeRenderer.kt` | `a8102bccd2` | `both-sides-clean` | expected | both sides changed `render(TypeName)` in different places |
| `moshi` `adapters/src/main/java/com/squareup/moshi/adapters/RuntimeJsonAdapterFactory.java` | `1896e0f118` | `both-sides-clean` | expected | left rewrote the class javadoc, right made the class public |
| `moshi` `kotlin/src/main/java/com/squareup/moshi/kotlin/KotlinJsonAdapter.kt` | `98c4358615` | `both-sides-clean` | expected | left changed the name handling, right moved the null check |
| `moshi` `moshi/src/main/java/com/squareup/moshi/JsonWriter.java` | `707f33226c` | `both-sides-clean` | expected | both sides changed `name(String)` in different places |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/vendors/SQLiteDialect.kt` | `b71568b295` | `both-sides-clean` | expected | left moved the function, right changed its signature |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/dao/Entity.kt` | `e179d32bf1` | `both-sides-clean` | expected | both sides changed the two `setValue` overrides and `flushInserts` |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/Queries.kt` | `e179d32bf1` | `both-sides-clean` | expected | both sides changed `insertAndGetId` in different places |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/Table.kt` | `e179d32bf1` | `both-sides-clean` | expected | both sides changed `nullable()` in different places |
| `Exposed` `src/test/kotlin/org/jetbrains/exposed/sql/tests/shared/DMLTests.kt` | `e179d32bf1` | `both-sides-clean` | expected | both sides changed `testGroupBy03`, `testGroupBy06` and `testInsert03` |
| `cats` `core/src/main/scala/cats/data/Ior.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/data/Validated.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/either.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/list.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/map.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/option.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/queue.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/seq.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/sortedMap.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/try.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/vector.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/package.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `free/src/test/scala/cats/free/FreeSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `kernel-laws/shared/src/test/scala/cats/kernel/laws/LawTests.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/EitherSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/FunctionSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/IndexedReaderWriterStateTSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/IndexedStateTSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/NestedSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/AsSuite.scala` | `6bf8d456b0` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/IndexedStateTSuite.scala` | `6bf8d456b0` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/NestedSuite.scala` | `6bf8d456b0` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `core/src/main/scala-2.13+/cats/instances/arraySeq.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `core/src/main/scala/cats/data/Chain.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `core/src/main/scala/cats/instances/list.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `core/src/main/scala/cats/instances/queue.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `core/src/main/scala/cats/instances/seq.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `core/src/main/scala/cats/instances/vector.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `tests/shared/src/test/scala/cats/tests/ApplicativeSuite.scala` | `a7c5ee9afe` | `both-sides-clean` | expected | left added the `replicateA_` test, right added the by-name ops test |
| `scala3` `compiler/src/dotty/tools/dotc/typer/Typer.scala` | `b873e5a76c` | `both-sides-clean` | expected | both sides changed the same methods in different places |
| `scala3` `compiler/src/dotty/tools/dotc/transform/PostTyper.scala` | `b6a95b255f` | `both-sides-clean` | expected | left changed the GADT constraint handling, right added the inline-trait and specialized checks |
| `scala3` `compiler/src/dotty/tools/dotc/typer/Typer.scala` | `980d135836` | `both-sides-clean` | expected | both sides changed the same methods in different places |
| `scala3` `tests/run-tasty-inspector/stdlibExperimentalDefinitions.scala` | `9521ae1b0b` | `both-sides-clean` | expected | each side added and removed different list entries |
| `scala3` `tests/run-tasty-inspector/stdlibExperimentalDefinitions.scala` | `1adb568dbb` | `both-sides-clean` | expected | each side added and removed different list entries |
| `scala3` `compiler/src/dotty/tools/dotc/core/SymDenotations.scala` | `0de561343f` | `both-sides-clean` | expected | left rewrote the exception handling, right added the `BadSymbolicReference` handling |
| `scala3` `compiler/src/dotty/tools/dotc/cc/Setup.scala` | `54bf0412cb` | `both-sides-clean` | expected | left added `initialVariance` and `addVar`, right added `copyAnnots` |
| `commons-lang` `src/test/java/org/apache/commons/lang3/StreamsTest.java` | `ee87df8472` | `conflict-one-side` | expected | left added `testToArray` above `asIntConsumer`, right changed its signature; the hunk starts at the method's first line |
| `Exposed` `exposed/src/main/kotlin/org/jetbrains/exposed/sql/Database.kt` | `a93cb8443c` | `conflict-one-side` | expected | right added `useNestedTransactions` above `metadata`, left changed its signature |
| `Exposed` `exposed/src/main/kotlin/org/jetbrains/exposed/sql/transactions/ThreadLocalTransactionManager.kt` | `7ca1c6bf39` | `conflict-one-side` | expected | left removed the two logging-exception functions, right reformatted them |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/statements/DeleteStatement.kt` | `b71568b295` | `conflict-one-side` | expected | left removed the blank line above `where`, right changed its signature |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/vendors/Default.kt` | `b71568b295` | `conflict-one-side` | expected | left removed `insert` and `replace`, right changed `delete` next to them; the hunk covers the three adjacent declarations |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/vendors/Mysql.kt` | `b71568b295` | `conflict-one-side` | expected | left moved `replace`, `insert` and `delete` to `MysqlFunctionProvider`, right changed `delete` in place |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/Op.kt` | `e179d32bf1` | `conflict-one-side` | expected | left reformatted the declarations, right changed them |
| `Exposed` `src/test/kotlin/org/jetbrains/exposed/sql/tests/shared/DMLTests.kt` | `e179d32bf1` | `conflict-one-side` | expected | left changed `testInsert03`, right reformatted it |
| `cats` `core/src/main/scala/cats/syntax/apply.scala` | `1889e48805` | `conflict-one-side` | expected | left changed the trait's supertype, right changed its first member |
| `cats` `project/TupleBifunctorInstancesBoiler.scala` | `531b51a386` | `conflict-one-side` | expected | left rewrote the `content` template, right re-indented the same lines |
| `cats` `project/TupleBitraverseInstancesBoiler.scala` | `531b51a386` | `conflict-one-side` | expected | left rewrote the `content` template, right re-indented the same lines |
| `cats` `project/TupleMonadInstancesBoiler.scala` | `531b51a386` | `conflict-one-side` | expected | left rewrote the `content` template, right re-indented the same lines |

### Wrong flag

These five files were reviewed on the first 2026-11-02 run, before the R29 fix. In the rerun four are no longer flagged; `Entity.kt` keeps only `both-sides-clean`. No issue was opened: a wrong flag is fixed in the validation (R29), not explained as a known limit.

| File | Merge commit | Flag | Verdict | Reason |
| --- | --- | --- | --- | --- |
| `commons-lang` `src/main/java/org/apache/commons/lang3/text/StrLookup.java` | `80644cdab9` | `conflict-one-side` | wrong flag | the conflict is in `systemPropertiesLookup`, changed on both sides; the flag came from `class StrLookup`, whose layout changed when the left moved members |
| `commons-lang` `src/main/java/org/apache/commons/lang3/StringUtils.java` | `9604c85306` | `conflict-one-side` | wrong flag | the conflict is in `containsAny(CharSequence, CharSequence...)`, changed on both sides; the flag came from `class StringUtils`, whose layout changed on the left |
| `Exposed` `spring-transaction/src/main/kotlin/org/jetbrains/exposed/spring/SpringTransactionManager.kt` | `7ca1c6bf39` | `conflict-one-side` | wrong flag | the conflicts are in `newTransaction` and `initTransaction`, changed on both sides; the flag came from `class SpringTransactionManager`, whose layout changed on the left |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/dao/Entity.kt` | `e179d32bf1` | `conflict-one-side` | wrong flag | the conflicts are in the two `setValue` overrides, changed on both sides; the flag came from `class Entity`, whose layout changed on the right |
| `cats` `alleycats-core/src/main/scala/alleycats/std/set.scala` | `78af8a7d66` | `conflict-one-side` | wrong flag | the conflict is in `alleyCatsStdSetMonad`, changed on both sides; the flag came from `trait SetInstances`, whose layout changed on the left |

### Fixed and known limit

No file has the verdict `fixed`: the review found no wrong outline. No file has the verdict `known limit`, and no issue is open for this run.

### Fallbacks

The six `status` files are all `PARTIAL`: some source in a Scala 3 file could not be parsed and was left unanalyzed, which is the specified fallback. No comparison threw, so the report says only `not ready` for the review and not `not ready: comparison threw in <file>` (R32).

| File | Merge commit | Side | Status | Reason |
| --- | --- | --- | --- | --- |
| `scala3` `compiler/src/dotty/tools/dotc/cc/Capability.scala` | `7744b8720d` | right | partial | some source could not be parsed and was not analyzed |
| `scala3` `library/src/scala/collection/mutable/LongMap.scala` | `14f423944c` | right | partial | some source could not be parsed and was not analyzed |
| `scala3` `library/src/scala/caps/package.scala` | `9521ae1b0b` | left | partial | some source could not be parsed and was not analyzed |
| `scala3` `library/src/scala/collection/Iterator.scala` | `9521ae1b0b` | left | partial | some source could not be parsed and was not analyzed |
| `scala3` `library/src/scala/collection/Iterator.scala` | `9521ae1b0b` | right | partial | some source could not be parsed and was not analyzed |
| `scala3` `library/src/scala/NamedTuple.scala` | `955de873ba` | right | partial | some source could not be parsed and was not analyzed |
| `scala3` `compiler/src/dotty/tools/dotc/typer/Implicits.scala` | `7c845272aa` | left | partial | some source could not be parsed and was not analyzed |

### Ready to ship

| Check | Result |
| --- | --- |
| R15: no file has `join-mismatch` | yes |
| R16: every `both-sides-clean` and `conflict-one-side` file is reviewed and fixed or explained | yes |
| R17: the median is at most twice the side comparison | yes, 29 ms against 23 ms |
| R32: no `status` file fell back because a comparison threw | yes |

## 2026-10-23: extraction detection

**Corpus:** the same Java and Kotlin list as the [0.1.0 noise pass](#2026-10-22-010-noise-pass) (1279 files from 234
commits) and the same [Scala](#2026-10-22-scala) list (596 files from 145 commits). The report gains two flags,
`extraction` for a file with a link and `extraction-near-miss` for a link that a covered or removed share from 0.5 up to
the threshold would make ([extraction detection](EXTRACTION-DETECTION.md) §8).

**First run, with the specified values:** 8 Java and Kotlin files had links, all right, but moshi `AdapterGenerator`
was missed. Its `generateFromJsonFun` body of about 375 lines moved whole into `generateFromJsonRegular`, and tiling
that compared every token pair of the two needed more than `MAX_WORK` on its own, so detection was skipped for the
file. Tiling now indexes the source's runs of `MIN_TILE` tokens (§4.6). A comparison of both on random token
sequences found the old tiling also took runs of 4 tokens, one short of `MIN_TILE`, when no longer run was left;
the indexed tiling takes the same tiles otherwise, so the covered shares below are slightly lower than in the first
run. The near misses showed real extractions below `MIN_SHARE`: commons-lang `containsTypeVariables(Type)` into
`containsTypeVariables(Type, Set<Type>)` and okhttp `EchOnFakeNetworkTest`, a third test sharing
`handshakerWithUpdatedEchConfigList`. Of the near misses under 0.65 only the moshi pairs below are real, and reaching them at 0.55 would also link the wrong guava and Exposed pairs. `MIN_SHARE` is therefore
0.65; the other values stay as specified.

**Thresholds:** `MIN_TOKENS` 10, `MIN_TILE` 5, `MIN_SHARE` 0.65, `MIN_REMOVED` 0.75, `MAX_WORK` 2 × 10^7 token
comparisons.

**Result:** 10 Java and Kotlin files and 8 Scala files have links, 24 added declarations in all, moshi
`generateFromJsonRegular` among them; the two extractions of the `noisy-change` fixture are linked too. Each link was checked against its
outline: the target's name and body size, the source and the old range the code came from. None is wrong. Typical
cases are test setup moved into helpers (okhttp `EchOnFakeNetworkTest`, `FakeNetworkOkHttpTest`, spring-petclinic
`ValidatorTests`), one helper taken from several sources (okhttp `handshakerWithUpdatedEchConfigList` from three
tests, commons-lang `getPackageName` from both `toFullyQualified` methods), an overload that takes over the body
(commons-lang `isAssignable`, zio `Supervisor.fibersIn`), and small predicates pulled out of a large method (scala3
`RefChecks.checkAllOverrides`, `TryCatchPatterns.isCatchCase`).

8 near misses remain (7 rows, because the guava row covers both copies of `Maps.java`):

| File | Near miss | Covered | Removed |
| --- | --- | --- | --- |
| guava `Maps.java` (both copies) | `lastEntry` ← `descendingEntryIterator` | 0.56 | 0.90 |
| Exposed `EntityCache.kt` | `trimToFirst` ← `maxEntitiesToStore` | 0.54 | 0.89 |
| moshi `-MoshiKotlinTypesExtensions.kt` | `subtypeOf` ← `subtypeOf`, `supertypeOf` ← `supertypeOf` | 0.55 | 0.94 |
| zio `test/package.scala` | `make` ← `live` | 0.58 | 0.98 |
| zio `ZTestLogger.scala` | `apply` ← `make` | 0.63 | 1.00 |
| zio `Annotations.scala` | `make` ← `live` | 0.85 | 0.64 |
| zio `TestRandom.scala` | `make` ← `make` | 0.82 | 0.67 |

The moshi pairs are the one real extraction left out: the no-argument `subtypeOf()` and `supertypeOf()` now call new
`KType` overloads, but most of each overload's body is new code. The crossed pairs in the first run, `subtypeOf` from
`supertypeOf`, show why a lower threshold would link the wrong source. The zio `make` ← `live` cases in `Annotations`
and `TestRandom` kept the code in place, so the removed share stops them as intended.

**Time:** the median stays at 4 ms for Java and Kotlin, against 4 ms in the noise pass run. The slowest file takes
145 ms (guava `Maps.java`, 155 ms before), and no file among the ten slowest is more than 20% slower in both of two
runs. All 1279 files together take 10.1 s and 9.9 s in two runs, against 9.4 s before, about 5 to 7% more, with 2%
between the two runs alone. The quadratic tiling of the first run had pushed the slowest file to 272 ms (guava
`LocalCache.java`), which now takes 54 ms. For Scala, main without extraction detection, run twice on the same base, took a median
of 24 and 23 ms, a slowest file of 578 and 605 ms (zio `ZIO.scala`) and 29.1 and 28.4 s in all; with it, 22.5 ms,
545 ms and 27.8 s. The difference is within the noise between runs. The 19 ms of the Scala run below predates two
Scala fixes on main.

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

Items 1, 2, 3 and 4 shipped in 0.1.1; item 5 remains a check for later Scala plugin releases. The text above and below
records the run before those fixes.

**After fixes 1, 2 and 4** (Scaladoc errors ignored, identical declarations unchanged despite parse errors, Scala 2 for
code without Scala 3 syntax), on the same corpus: 595 files `ANALYZED` and 1 `PARTIAL` (scala3 `Implicits.scala`,
where the changed declaration itself has a parser gap), against 536 and 60; 1 unanalyzed row, against 163. All 307
cats files now take Scala 2, zio 109 of 118 (the other 9 are in `scala-3/` source directories or use top-level
definitions) and scala3 17 of 171, 433 in all. 21 files are flagged, against 79: `rename` 10, `noisy` 7,
`rename-candidate` 4, `same-name` 1, `status` 1; the trait-to-class files remain. The median time was 23 ms and the
slowest file took 619 ms (zio `ZStream.scala`), since files without Scala 3 syntax are now parsed in both dialects.

**After the kind-change fix** (fix 3, rule M4b), on the same corpus and with extraction detection in both runs: 29 files
are flagged, against 33 with the rule turned off: `rename` 9 (10), `noisy` 5 (7), `same-name` 0 (1), the extraction
flags unchanged (8 and 4), `rename-candidate` 4 and `status` 1. The three cats trait-to-class files are clean:
`Representable.scala` shows `trait → class RepresentableMonad` and `RepresentableBimonad` and nothing else (11
reported rows before, 2 now), `Func.scala` shows `trait → class AppFuncApplicative` with its members in place (9 to 4),
and in `IndexedReaderWriterStateT.scala` `class → trait IRWSTFunctor` keeps its unchanged `implicit def F` unreported,
while the `F` really added to `RWSTAlternative` stays added (9 to 5). scala3 `ScaladocConfigs0.scala` shows its two
`def` → `val` changes as `function → property dottyExternalMapping` and `javaExternalMapping` (15 to 13 rows); the
rest of that file is a real rewrite. Every file is still `ANALYZED` but scala3 `Implicits.scala`; the median time was
22 ms.

## 2026-10-22: 0.1.0 noise pass

**Corpus:** the same 234 commits and 1279 files as before 0.1.0, from the same clones.

**Overall:** every file was `ANALYZED`. The median analysis time was 4 ms and the slowest file took 158 ms (okhttp
`CallTest.kt`). The nothing-hidden check is now exact and about linear for local changes, so it ran on all 1279 files
(865 before): none broke the rule and none had a difference with nothing reported.

84 files were flagged for review, against 100 before 0.1.0:

| Flag | Before the noise pass | After the noise pass (0.1.0) |
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
changed shell plus an added method, as [extraction detection](ROADMAP.md#extraction-detection) describes. Linked since
[extraction detection](#2026-10-23-extraction-detection).

### Labels hard to read

Removing all whitespace from signatures (§6.6) also removes it from the labels: `Class<outAnnotation>`,
`Multiset<?extendsE>`. A Kotlin extension function without parameters reads `formatIsoDate(Date.)`. Proposed fix:
collapse whitespace to one space where it separates words, and show the receiver before the name
(`Date.formatIsoDate()`).

### Not a problem

- `noisy` (16 files): every case was a large addition of new tests or classes, correctly reported as added.
- `same-name` (1 file): okhttp `FakeSocket.kt`, where `close()` was removed from two classes and added to a new one;
  correct.
