# Refactorings view screenshots

These images are **automated evidence only**. They show the two-sided outline and both editors for the refactorings view
introduced in [relationships/06](../../specs/relationships/06-refactorings-view.md), rendered from the real diff viewer
and outline by `RefactoringsScreenshotTest` without opening a window. The build environment has no interactive display,
so the [manual IDE check](../../VALIDATION.md#2026-11-09-refactorings-view-manual-check) remains outstanding and this folder
does not record a manual pass. The dark images use the Darcula editor scheme and the Darcula look and feel.

| Image | Shows |
| --- | --- |
| `java-rename-usages-refactorings-light.png` | Java rename with a grouped usage, the `high` row, and the usage colour in both editors |
| `java-rename-usages-refactorings-dark.png` | The same view in the dark theme |
| `java-rename-usages-selected-light.png` | The rename row selected, framing the declaration and highlighting its usage |
| `java-rename-usages-container-light.png` | The container tree, unchanged, as R18 requires |
| `java-rename-usages-changed-hidden.png` | The change type filter in the refactorings view |
| `java-rename-usages-rename-hidden.png` | Rename hidden by kind: `No refactorings found`, and the declaration under `Other changes` |
| `java-move-refactorings.png` | A moved declaration as one row |
| `kotlin-extraction-refactorings.png` | A Kotlin extraction as one row |
| `scala-inlining-refactorings.png` | A Scala inlining as one row |

Regenerate them with:

```bash
SMARTDIFF_SCREENSHOTS=1 ./gradlew test --tests 'io.github.agadek.smartdiff.ui.RefactoringsScreenshotTest'
```
