# Usability study tasks

Status: prepared, 2026-11-10

Each task is one file from a real commit in a corpus repository. The facilitator prepares the file at the parent commit and leaves the commit's version as an uncommitted change, so the diff is shown in Local Changes. The question has one expected answer: the declaration and how it changed.

Do not show this file to participants. The facilitator reads only the question in the task row.

| ID | Repository | Commit | Parent | File | Language | Question | Expected answer |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T1 | `spring-petclinic` | `e0db9b184e028d41bcb626f3cbf03a942f67e104` | `51045d1648dad955df586150c1a1a6e22ef400c2` | `src/main/java/org/springframework/samples/petclinic/owner/PetController.java` | Java | Which private method was added to recognise a duplicate pet-name violation? | `isDuplicatePetNameViolation(DataIntegrityViolationException)` was added. The outline row is `method isDuplicatePetNameViolation(DataIntegrityViolationException)  added`; the two `process…` methods around it are changed. |
| T2 | `okhttp` | `a2cf5aa9f8711dabe514871e9dc4a9336a0e403f` | `5410de9760bce8719129c3d08eb19e5bace75f6e` | `okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RetryTlsHandshake.kt` | Kotlin | Which existing function was renamed, and what is its new name? | `retryTlsHandshake` was renamed to `attemptAnotherConnectionSpec`. The outline row is `function retryTlsHandshake → attemptAnotherConnectionSpec(IOException)  rename candidate, changed`. The separate `attemptAnotherConnection` is added. |
| T3 | `zio` | `01ec1b457072f9ea29f70462b268d13068d69a06` | `bcbd0cefa72d1a06e743f12223712c0961bbb323` | `concurrent/src/main/scala/zio/concurrent/CyclicBarrier.scala` | Scala 2 | How did the member `break` change? | It changed from a property to a function, with a changed signature. The outline row is `property → function break  changed, signature changed`. |
| T4 | `scala3` | `878f4dba7670fa35df4388b2a434e8674df7fc34` | `32e78d4f88ac3084d1854d885c27991c26447f05` | `compiler/src/dotty/tools/dotc/parsing/Parsers.scala` | Scala 3 | Which function was removed from `Parsers`? | `checkNonParamTuple` was removed. The outline row is `function checkNonParamTuple(Tree)  removed`. The nearby `checkNoEscapingPlaceholders`, `wrapPlaceholders` and `binding` rows are changed. |
| T5 | `Exposed` | `80f98e796e6567ce86e1816f21edc9a9fe9002b3` | `38b0f398b3791a470425890de26b73a6b7f65c8a` | `exposed-core/src/main/kotlin/org/jetbrains/exposed/v1/core/LiteralOp.kt` | Kotlin | Which function was added as the generic scalar literal helper? | `literal(value: T)` was added. The outline row is `function literal(T)  added`; the existing `asLiteral` row is changed. |

## Preparing a task

The corpus clones are under the maintainer's corpus root. For task `<ID>`:

```shell
./docs/usability-study/prepare-task.sh <corpus-root> <ID> /tmp/usability-T1
```

The script creates a small Git repository with the file at the parent commit, commits that base, and writes the task version as an uncommitted change. Open `/tmp/usability-T1` in the IDE; Local Changes shows the one file. Do not run `git add` or `git commit` in that repository before the participant sees it.

The script needs `git` and the pinned corpus clone. It writes no participant data.

## Why these tasks

- T1 is Java, T2 and T5 are Kotlin, T3 is Scala 2 and T4 is Scala 3, so all supported languages appear.
- T2 is the rename-candidate case; T3 is the kind-change case.
- Each file changes at least two declarations in at least two hunks outside each other's context, as the details require.
- T1's added method, T2's rename, T3's kind change, T4's removal and T5's added helper each have one unambiguous answer.

If a task's outline does not show the expected answer in the dry run, replace that task with another pinned commit and record the replacement before the study.
