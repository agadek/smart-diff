# Usability validation

Status: proposed, 2026-11-01

**Description:** A small, repeatable study compares how quickly reviewers find changed [declarations](../../GLOSSARY.md#declaration) with the [structural outline](../../GLOSSARY.md#structural-outline) and with the [native diff](../../GLOSSARY.md#native-diff) alone.

**Use case:** The maintainer needs to know whether the outline meets its main goal, that a reviewer finds changed declarations faster than by scanning the text diff. Corpus results measure analysis quality, not reviewer time, so this feature supplies the missing evidence before a release claims the benefit.

## Requirements

- **R1** A study uses at least 5 review tasks, each from a real multi-hunk change in a supported language.
- **R2** Each task has one expected answer: the declaration that contains the change and how it changed.
- **R3** At least 5 participants take part, and each knows the task's language but not the change.
- **R4** Each participant does every task once with the outline and once with the native diff alone.
- **R5** The order of the two conditions alternates between tasks and participants.
- **R6** The study records time to answer and whether the answer was right, for each task and condition.
- **R7** The outline passes when the median time is at least 20% shorter than with the native diff and the number of wrong answers does not rise.
- **R8** The method, the tasks and the results are recorded in [VALIDATION.md](../../VALIDATION.md), separate from the corpus classification results.
- **R9** A later study can repeat the same tasks and compare its results with the recorded baseline.
- **R10** The study never records names or other personal data of participants.

## Out of scope

- A public benchmark or a comparison with other diff products.
- Measuring long-term use in the IDE.
- Changing the outline during the study.

## References

- [Details: method and analysis](06-usability-validation-details.md)
- [Product scope](../README.md#product-scope)
- [Validation](../../VALIDATION.md)
- [Relationship benchmark](../relationships/01-relationship-benchmark.md)
