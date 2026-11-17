# Fine-grained differencing

Status: implemented, 2026-11-17, except the validation against Difftastic and GumTree (R21, R22), which is still proposed

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) compares the tokens of each [changed](../../GLOSSARY.md#changed) [declaration](../../GLOSSARY.md#declaration) and reports its [changed runs](../../GLOSSARY.md#changed-run): the tokens that differ once whitespace is compared as for [formatting-only](../../GLOSSARY.md#formatting-only) changes. The [SMART Diff viewer](../../GLOSSARY.md#smart-diff-viewer) highlights those runs inside changed lines instead of the [native diff](../../GLOSSARY.md#native-diff)'s word highlights, so reformatted code inside a changed declaration is not highlighted.

**Use case:** A reviewer reads a method that a teammate reformatted and in which they changed one condition. The native diff highlights every re-wrapped argument and re-indented line, so the reviewer reads all of them to find the edit. In the SMART Diff viewer only `>=` and the new `limit` argument are highlighted, and the lines that were only re-wrapped keep their line colour without word highlights.

## Requirements

- **R1** "Highlight changed tokens in the SMART Diff viewer" is on by default, and with it off the SMART Diff viewer is the same as without this feature.
- **R2** For each changed or formatting-only declaration matched on both sides, and for a changed or formatting-only [file region](../../GLOSSARY.md#file-region), the declaration engine compares its tokens and reports its changed runs; a formatting-only one has none.
- **R3** The engine compares a declaration's own source token by token, by token type and text, leaving out the source of its child declarations.
- **R4** Whitespace takes part in the comparison only as far as the language's formatting rules make it significant.
- **R5** A comment is compared by its exact text, and a changed comment is a changed run.
- **R6** Changed tokens that are next to each other, or separated only by whitespace, form one changed run.
- **R7** Tokens that keep their text but change their order give at least one changed run on each side.
- **R8** Every token the comparison finds different lies in a changed run.
- **R9** A declaration with a [parse error](../../GLOSSARY.md#parse-error) on either side, or [unanalyzed](../../GLOSSARY.md#unanalyzed) content, has no changed runs.
- **R10** A declaration with more than 20 000 own tokens on either side, or an edit distance of more than 2 000 removed and added tokens together, is not compared by tokens and has no changed runs.
- **R11** In the SMART Diff viewer, a native change whose lines on both sides lie inside declarations or the file region compared by tokens highlights only those runs within its lines, in place of the native word highlights.
- **R12** Such a native change whose lines hold no changed run, including a change with lines on one side only, keeps its line colour and shows no word highlights.
- **R13** A native change with a line outside every declaration and file region compared by tokens keeps the native word highlights.
- **R14** The SMART Diff viewer keeps the native changes' lines, their order, next and previous change, and their gutter actions.
- **R15** With the viewer's highlighting set to anything other than words or characters, the SMART Diff viewer highlights as the native viewer does.
- **R16** Nothing is highlighted from changed runs while an edit is compared again, or while the outline is off; the native word highlights show then.
- **R17** Selecting a changed declaration's row in the [structural outline](../../GLOSSARY.md#structural-outline) highlights its changed runs on both sides.
- **R18** Changing the setting applies to open SMART Diff viewers without comparing the file again.
- **R19** The outline's rows, [labels](../../GLOSSARY.md#label) and [summary line](../../GLOSSARY.md#summary-line), the [Changes tree](../../GLOSSARY.md#changes-tree), the merge outline and the classification of every declaration are the same as without this feature.
- **R20** The native side-by-side viewer and the unified viewer are the same as without this feature.
- **R21** The validation compares the changed runs with Difftastic and GumTree on the validation corpus and records the results in the validation record.
- **R22** The validation passes when no reviewed declaration has a real edit outside its changed runs.
- **R23** Changed runs never say that two versions behave the same, and the native diff keeps showing every changed line.

## Out of scope

- Matching syntax-tree nodes inside a declaration, as GumTree does, and showing an expression as moved.
- Code moved inside or between declarations, which [moves inside declarations](../../ROADMAP.md#moves-inside-declarations) covers.
- The unified viewer, [three-sided diffs](../../GLOSSARY.md#three-sided-diff), the merge window and the [line comparison](../../GLOSSARY.md#line-comparison).
- Leaving rewrites out of the changed runs when an [equivalence rule](../../GLOSSARY.md#equivalence-rule) names them; a rewritten literal stays a changed run.
- Shipping Difftastic or GumTree with the plugin.

## References

- [Details: design and tests](10-fine-grained-differencing-details.md)
- [ADR 0014](../../adr/0014-changed-runs-come-from-the-declaration-engine.md)
- [ADR 0001](../../adr/0001-declaration-level-first-release.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Change classification](02-change-classification.md)
- [SMART Diff viewer](../outline/04-smart-diff-viewer.md)
- [Settings page](../settings/01-settings-page.md) R23
- [Roadmap: Fine-grained differencing](../../ROADMAP.md#fine-grained-differencing)
