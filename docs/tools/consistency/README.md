# Documentation consistency checks

```bash
make check-docs
```

Four mechanical checks over this repository's documentation. No containers, no network, no build —
a checkout and Python. CI runs it as its own job.

| Check | Compares | Catches |
|---|---|---|
| Relative links | every relative markdown link in every `.md` | a moved or renamed file |
| Section references | every `<doc> §N`, **including from `.java`, `.yml`, `.sql` and `.ts`** | a renumbered section the referring file cannot see |
| ADR inventory | `docs/adr/` against the list in [`../../agents/domain.md`](../../agents/domain.md) | a new ADR nobody added to the list |
| Migration inventory | `db/migration/` against the table in [`../../03-data-model.md`](../../03-data-model.md) | a migration the data model never learned about |

## Why these four

Phase 11's last Documentation row is *"a final consistency pass over `/docs` and `CONTEXT.md`"*. A
pass run once is out of date the next time somebody renumbers a section, so this is the pass as a
target rather than as an event.

**None of them reads prose, and none checks a document against itself.** Each compares one document
against something capable of contradicting it — the filesystem, the migration directory, another
document's headings. A check written the other way proves only that the document says what the
document says, which is [T42](../../sessions/README.md): two security headers were documented for
ten phases before any file set them.

**Section references are the interesting one.** This repo cross-references by section number rather
than by markdown anchor, and it does so *from code* — `application.yml`, `V7__ai.sql` and a dozen
Java classes all cite `docs/06-security.md §N`. Renumbering a document silently invalidates
references that live in files the document's author never opens. There are 267 of them.

## Every check prints how many things it looked at

An empty population is reported as **SKIPPED and fails the run**, rather than passing.

This is not hypothetical. An anchor-link check was written first and removed: it reported that every
anchor link resolved, which was true, because this corpus contains **zero** anchor links across
ninety files. A check that has quietly lost its subject reports exactly what a passing check reports.
If a count here ever drops to zero, that is the failure.

## Shown red

Each check was planted against before being trusted:

| Plant | Result |
|---|---|
| A markdown file linking to a path that does not exist | relative links **FAIL** |
| The same file citing section 99 of the security document, which has 15 | section references **FAIL** |
| An ADR added to `docs/adr/` and not to the list | ADR inventory **FAIL** |
| `V10` removed from the data model's migration table | migration inventory **FAIL** |

## Examples in fenced blocks are ignored

A document explaining link syntax has to be able to show link syntax. Fenced blocks are stripped
before either scan, so this README can demonstrate a broken link without failing the check that
finds broken links.

**Inline code spans are deliberately NOT stripped.** Most real references here backtick the filename
and leave the section number outside it, and stripping those would quietly shrink the population
this check is counted on to cover — which is the failure the count exists to make visible.

## What it does not check

Prose. Nothing here can tell whether a sentence is *true* — only whether the things it points at
exist. The claim that a document describes a control no file implements is **G26**, it is still open,
and it has been found twice by a person reading prose beside a file rather than by any gate.
