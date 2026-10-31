# Engine contract

Status: implemented, 2026-10-21

**Description:** Rules that every [comparison engine](../../GLOSSARY.md#comparison-engine) follows, so the outline can trust any engine's result in the same way.

**Use case:** A reviewer relies on the outline to find every change in a file. Whatever engine produced the outline, no changed code is missing from it.

## Requirements

- **R1** Nothing hidden: every character that differs between the sides lies in a reported change, the [file region](../../GLOSSARY.md#file-region) or [unanalyzed](../../GLOSSARY.md#unanalyzed) source.
- **R2** The same two texts and the same engine version always give the same result.
- **R3** An engine runs in the background, never on the UI thread.
- **R4** An engine stops soon after it is cancelled.
- **R5** An engine reports a failure as a result with a reason, not as an exception.
- **R6** An exception from an engine is shown as a failed analysis.
- **R7** All ranges in a result lie inside the text of their side.
- **R8** Identical texts give no reported changes.
- **R9** Empty texts, and a text that is empty on one side, give a valid result.
- **R10** No source code leaves the IDE process.
- **R11** Every engine passes the shared contract test suite.
- **R12** A test-only engine passes the suite, appears in the engine dropdown and can be selected, with no change to the UI code.

## References

- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Technical specification §3.1 and §12](../../TECHNICAL-SPECIFICATION.md#31-contract)
