# Kind changes

Status: implemented, 2026-10-23

**Description:** A declaration that keeps its name but changes [kind](../../GLOSSARY.md#kind), such as a class turned into a trait, is shown as one [kind change](../../GLOSSARY.md#kind-change).

**Use case:** A reviewer opens a diff where a Scala class became a trait. They see `class → trait Shape` once, with its members in place, instead of the class removed, the trait added and every member moved.

## Requirements

- **R1** A removed and an added declaration with the same name and different kinds of one family are paired.
- **R2** One family is types: class, trait, interface, enum, record and annotation type.
- **R3** The other family is value members: method, function, field and property.
- **R4** An object never pairs with a class this way.
- **R5** They are paired only when the name occurs once on each side in matched [containers](../../GLOSSARY.md#container), or at the top level.
- **R6** A kind change counts as a [signature change](../../GLOSSARY.md#signature-changed).
- **R7** Its label shows both kinds: `trait → class RepresentableMonad`.
- **R8** The members of a type that changed kind keep their place and match as usual.

## References

- [Technical specification §7, rule M4b](../../TECHNICAL-SPECIFICATION.md#7-declaration-engine-matching)
- [Validation, Scala](../../VALIDATION.md#2026-10-22-scala)
