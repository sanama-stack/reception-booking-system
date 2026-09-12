#!/usr/bin/env python3
"""Consistency checks over this repository's documentation.

Phase 11's last Documentation row is "a final consistency pass over /docs and CONTEXT.md". A pass
run once is out of date the next time somebody renumbers a section, so it is this instead: four
mechanical checks that can be run again.

None of them reads prose. Each compares one document against something that can contradict it —
the filesystem, the migration directory, or another document's headings — because a check that only
reads the document it is checking proves that the document says what the document says (T42).

EVERY CHECK PRINTS HOW MANY THINGS IT LOOKED AT. An empty population is reported as SKIPPED rather
than as a pass: the anchor-link check that used to live here found zero anchor links in ninety
files and said "all resolve", which is true and means nothing. If a count here ever drops to zero,
the check has stopped reaching its subject.
"""

import os
import re
import sys
import urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
SKIP = ('/node_modules', '/.git', '/build', '/.next', '/target', '/.gradle', '/dist')

failures = []

FENCE = re.compile(r'^\s*(```|~~~)', re.M)


def without_fenced_blocks(text):
    """Drop fenced code blocks.

    A document that explains link syntax has to be able to show link syntax, and a checker that
    cannot tell an example from a reference pushes people to write worse documentation to keep it
    quiet. Fenced blocks only: inline code spans are NOT stripped, because most real references in
    this repo backtick the filename and leave the section number outside it, and stripping those
    would quietly shrink the population this check is counted on to cover.
    """
    out, fenced = [], False
    for line in text.split('\n'):
        if FENCE.match(line):
            fenced = not fenced
            out.append('')
            continue
        out.append('' if fenced else line)
    return '\n'.join(out)




def report(name, count, subject, bad, render):
    """One check's result. `bad` empty and `count` non-zero is the only pass."""
    if count == 0:
        print(f"  SKIPPED  {name}: found no {subject} to check — the check has lost its subject")
        failures.append(name)
        return
    if bad:
        print(f"  FAIL     {name}: {len(bad)} of {count} {subject}")
        for item in bad:
            print(f"             {render(item)}")
        failures.append(name)
    else:
        print(f"  ok       {name}: {count} {subject}")


def walk(exts):
    for d, _, fs in os.walk(ROOT):
        if any(p in d for p in SKIP):
            continue
        for f in fs:
            if f.endswith(exts):
                yield os.path.join(d, f)


def markdown():
    return sorted(walk(('.md',)))


# --- 1. every relative link resolves to a file that exists ----------------------------
def check_links():
    pattern = re.compile(r'\[([^\]]*)\]\(([^)]+)\)')
    count, bad = 0, []
    for path in markdown():
        for m in pattern.finditer(without_fenced_blocks(open(path, encoding='utf-8').read())):
            target = m.group(2).strip()
            if target.startswith(('http://', 'https://', 'mailto:', '#')):
                continue
            target = urllib.parse.unquote(target.split('#')[0])
            if not target:
                continue
            count += 1
            if not os.path.exists(os.path.normpath(os.path.join(os.path.dirname(path), target))):
                bad.append((os.path.relpath(path, ROOT), target))
    report("relative links", count, "links", bad, lambda b: f"{b[0]} -> {b[1]}")


# --- 2. every "<doc> §N" points at a section that exists ------------------------------
# This repo cross-references by section number rather than by anchor, from prose AND from code
# comments, which is why this check reads .java, .yml and .sql too. Renumbering a document
# silently invalidates references the document cannot see.
def check_sections():
    heading = re.compile(r'^#{2,4}\s+(\d+(?:\.\d+)?)\.?\s')
    sections = {}
    for path in markdown():
        nums = {m.group(1) for m in
                (heading.match(line) for line in open(path, encoding='utf-8')) if m}
        if nums:
            sections[os.path.relpath(path, ROOT)] = nums

    ref = re.compile(r'([A-Za-z0-9_./-]*\d\d-[a-z-]+\.md|CONTEXT\.md|README\.md)\s*§+\s*(\d+(?:\.\d+)?)')
    count, bad = 0, []
    for path in walk(('.md', '.java', '.ts', '.tsx', '.yml', '.yaml', '.sql', '.kts')):
        try:
            text = open(path, encoding='utf-8').read()
        except (UnicodeDecodeError, OSError):
            continue
        if path.endswith('.md'):
            text = without_fenced_blocks(text)
        for m in ref.finditer(text):
            count += 1
            doc, num = m.group(1), m.group(2)
            base = os.path.basename(doc)
            hits = [k for k in sections if os.path.basename(k) == base]
            if not hits:
                bad.append((os.path.relpath(path, ROOT), doc, num, "no such document"))
            elif not any(num in sections[k] for k in hits):
                bad.append((os.path.relpath(path, ROOT), doc, num, "no such section"))
    report("section references", count, "'<doc> §N' references", bad,
           lambda b: f"{b[0]}: {b[1]} §{b[2]} — {b[3]}")


# --- 3. the ADR list in docs/agents/domain.md matches docs/adr/ -----------------------
def check_adrs():
    on_disk = sorted(f for f in os.listdir(os.path.join(ROOT, 'docs/agents/../adr'))
                     if re.match(r'\d{4}-.*\.md$', f))
    listed = sorted(set(re.findall(r'(\d{4}-[a-z0-9-]+\.md)',
                                   open(os.path.join(ROOT, 'docs/agents/domain.md'),
                                        encoding='utf-8').read())))
    bad = [("missing from docs/agents/domain.md", f) for f in on_disk if f not in listed]
    bad += [("listed but not in docs/adr/", f) for f in listed if f not in on_disk]
    report("ADR inventory", len(on_disk), "ADRs", bad, lambda b: f"{b[1]} — {b[0]}")


# --- 4. the migration table in docs/03-data-model.md matches the migration directory ---
def check_migrations():
    mig_dir = os.path.join(ROOT, 'backend/src/main/resources/db/migration')
    on_disk = sorted(f for f in os.listdir(mig_dir) if f.endswith('.sql'))
    doc = open(os.path.join(ROOT, 'docs/03-data-model.md'), encoding='utf-8').read()
    listed = set(re.findall(r'(V\d+__[a-z0-9_]+\.sql)', doc))
    bad = [("missing from docs/03-data-model.md", f) for f in on_disk if f not in listed]
    bad += [("listed but not in db/migration", f) for f in sorted(listed) if f not in on_disk]
    report("migration inventory", len(on_disk), "migrations", bad, lambda b: f"{b[1]} — {b[0]}")


print("Documentation consistency (docs/tools/consistency)")
check_links()
check_sections()
check_adrs()
check_migrations()

if failures:
    print(f"\nFAILED: {', '.join(failures)}")
    sys.exit(1)
print("\nEvery check passed, and each one found something to check.")
