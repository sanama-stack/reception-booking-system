#!/usr/bin/env python3
"""Reconcile the Makefile's documented targets against the commands CI actually runs.

G41: nothing made the documented local build and the pipeline agree, so they could diverge in
silence. They had. `make test` was documented as "Run backend and frontend test suites" and its
frontend half ran `lint`, `typecheck` and `build` — no `pnpm test`, so 82 tests were skipped by the
one command a reader is told to run, for ten phases, while CI ran them and agreed with nobody
(issue #36, T150). `make e2e` had the same shape: CI typechecks the Playwright project, the target
did not.

WHAT IS DERIVED AND WHAT IS NOT. The commands are derived from both sides — the CI workflow's steps
and the Makefile's recipes are parsed, and the script names are checked against each project's
package.json, so a renamed script is a failure rather than a silent miss. The three-row table below
is the hand-written part, and it is the irreducible one: something has to state that `make test` is
the local stand-in for the Backend and Frontend jobs. That table's own hole is closed by
check_every_ci_directory_is_covered — a CI step that works in a fourth directory fails here
instead of going uncovered.

WHAT IS COMPARED IS THE SCRIPT NAME, NOT THE COMMAND LINE. `--no-daemon` is a runner's concern and
`--with-deps` is deliberately absent from `make e2e` (see that target's comment). Flags are where
CI and a laptop are *supposed* to differ; which suites run is where they are not.

IT FAILS IN BOTH DIRECTIONS. A script CI runs and the target does not is the defect that produced
this file. A script the target runs and CI does not is the same silent divergence pointing the
other way — a local gate the pipeline will not hold anyone to.

EVERY CHECK PRINTS HOW MANY THINGS IT LOOKED AT, and an empty population FAILS rather than passes.
This file parses two formats by hand; if either file's shape changes under it, the honest outcome
is a red check that says it lost its subject, not a green one over nothing. That guard is not
theoretical here — see docs/tools/consistency/check.py's header for the two scans of nothing that
printed clean results in this repository.
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WORKFLOW = os.path.join(ROOT, '.github/workflows/ci.yml')
MAKEFILE = os.path.join(ROOT, 'Makefile')

# The hand-written rows: which make target is the documented local stand-in for CI's work in a
# directory. Every directory CI works in must appear here, and every directory here must be one CI
# works in — both asserted below.
PAIRS = [
    ('backend', 'test'),
    ('frontend', 'test'),
    ('e2e', 'e2e'),
]

failures = []


def report(name, count, subject, bad, render=str):
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


# --- reading the workflow ------------------------------------------------------------
# Enough YAML for this file's shape and no more: job keys at indent 2 under `jobs:`, steps at
# indent 6, and each step's `working-directory` and `run` (plain or block scalar). PyYAML is not
# imported because the docs job that this one sits beside needs nothing but a checkout and
# python3, and that is worth keeping true.
def read_workflow():
    steps, job, step = [], None, None
    in_jobs = False
    for raw in open(WORKFLOW, encoding='utf-8'):
        line = raw.rstrip('\n')
        if re.match(r'^jobs:\s*$', line):
            in_jobs = True
            continue
        if not in_jobs:
            continue
        m = re.match(r'^  ([A-Za-z0-9_-]+):\s*$', line)
        if m:
            job, step = m.group(1), None
            continue
        m = re.match(r'^      - (.*)$', line)
        if m:
            step = {'job': job, 'dir': None, 'runs': [], 'block': None}
            steps.append(step)
            line = '        ' + m.group(1)
        if step is None:
            continue
        m = re.match(r'^        working-directory:\s*(\S+)\s*$', line)
        if m:
            step['dir'] = m.group(1)
            step['block'] = None
            continue
        m = re.match(r'^        run:\s*(.*)$', line)
        if m:
            body = m.group(1).strip()
            if body in ('|', '>', '|-', '>-'):
                step['block'] = True
            else:
                step['runs'].append(body)
                step['block'] = None
            continue
        if re.match(r'^        [A-Za-z0-9_-]+:', line):
            step['block'] = None
            continue
        if step['block'] and line.strip():
            step['runs'].append(line.strip())
    return steps


# --- reading the Makefile ------------------------------------------------------------
# A recipe is the tab-indented lines following the target. Blank lines and column-0 comments do
# NOT end it — `make e2e` carries twenty lines of comment between its guard and its command — so
# scanning continues until a line that starts a new target or variable.
def read_recipe(target):
    lines, seen = [], False
    for raw in open(MAKEFILE, encoding='utf-8'):
        line = raw.rstrip('\n')
        if not seen:
            if re.match(rf'^{re.escape(target)}:', line):
                seen = True
            continue
        if line.startswith('\t'):
            lines.append(line[1:])
        elif line.strip() and not line.startswith('#'):
            break
    return lines


# Each recipe line is its own shell, so `cd` is scoped to the line it appears on.
def recipe_commands(target):
    out = []
    for line in read_recipe(target):
        cwd = ''
        for segment in line.split('&&'):
            segment = segment.strip().lstrip('@')
            m = re.match(r'^cd\s+(\S+)$', segment)
            if m:
                cwd = m.group(1)
                continue
            out.append((cwd, segment))
    return out


# --- what counts as "a suite ran" ----------------------------------------------------
def package_scripts(directory):
    path = os.path.join(ROOT, directory, 'package.json')
    if not os.path.exists(path):
        return set()
    return set(json.load(open(path, encoding='utf-8')).get('scripts', {}))


# pnpm's own subcommands. A word here runs no package script, so it is not a suite and its
# absence from package.json is not a defect.
PNPM_BUILTINS = {'install', 'exec', 'dlx', 'add', 'remove', 'why', 'store'}


def invocations(command, scripts):
    """The package scripts and gradle tasks one command line runs, and the names that resolved
    to nothing.

    A bare word after `pnpm` counts as a suite only if package.json declares it. A word that is
    neither a declared script nor a pnpm subcommand is returned as unresolved rather than dropped:
    dropping it is how a renamed script would disappear from BOTH sides at once and leave two sets
    that agree about nothing, which is the false green this check exists to refuse.
    """
    found, unresolved = set(), set()
    m = re.match(r'^pnpm\s+(?:run\s+)?([A-Za-z0-9:_-]+)', command)
    if m:
        name = m.group(1)
        if name in scripts:
            found.add(name)
        elif name not in PNPM_BUILTINS:
            unresolved.add(name)
    m = re.match(r'^\./gradlew\s+(.*)$', command)
    if m:
        found |= {t for t in m.group(1).split() if not t.startswith('-')}
    return found, unresolved


def ci_invocations(steps, directory):
    scripts = package_scripts(directory)
    found, unresolved = set(), set()
    for step in steps:
        if step['dir'] == directory:
            for run in step['runs']:
                f, u = invocations(run, scripts)
                found |= f
                unresolved |= u
    return found, unresolved


def make_invocations(target, directory):
    scripts = package_scripts(directory)
    found, unresolved = set(), set()
    for cwd, command in recipe_commands(target):
        if cwd == directory:
            f, u = invocations(command, scripts)
            found |= f
            unresolved |= u
    return found, unresolved


# --- 1. the workflow was read at all --------------------------------------------------
def check_workflow_was_read(steps):
    # Scoped to the `jobs:` block. `on:` declares `push` and `pull_request` at the same indent,
    # and counting those as jobs made this check red against a workflow it had read correctly.
    declared, in_jobs = set(), False
    for line in open(WORKFLOW, encoding='utf-8'):
        if re.match(r'^jobs:\s*$', line):
            in_jobs = True
            continue
        if re.match(r'^[A-Za-z0-9_-]+:', line):
            in_jobs = False
        m = re.match(r'^  ([A-Za-z0-9_-]+):\s*$', line)
        if in_jobs and m:
            declared.add(m.group(1))
    parsed = {s['job'] for s in steps}
    bad = [(j, "declared but no step was read from it") for j in sorted(declared - parsed)]
    report("workflow jobs", len(declared), "jobs", bad, lambda b: f"{b[0]} — {b[1]}")
    report("workflow steps", len(steps), "steps", [], str)


# --- 2. every directory CI runs package scripts in has a row in PAIRS -----------------
def check_every_ci_directory_is_covered(steps):
    dirs = {s['dir'] for s in steps if s['dir']}
    covered = {d for d, _ in PAIRS}
    bad = [(d, "CI runs commands here and no make target is paired with it")
           for d in sorted(dirs - covered)]
    bad += [(d, "paired with a make target and CI runs nothing here")
            for d in sorted(covered - dirs)]
    report("directory coverage", len(dirs), "directories CI works in", bad,
           lambda b: f"{b[0]} — {b[1]}")


# --- 3. the suites agree, in both directions ------------------------------------------
def check_suites_agree(steps):
    count, bad = 0, []
    for directory, target in PAIRS:
        ci, _ = ci_invocations(steps, directory)
        mk, _ = make_invocations(target, directory)
        count += len(ci | mk)
        for name in sorted(ci - mk):
            bad.append((directory, name, f"CI runs it; `make {target}` does not"))
        for name in sorted(mk - ci):
            bad.append((directory, name, f"`make {target}` runs it; CI does not"))
    report("suite parity", count, "scripts and tasks", bad,
           lambda b: f"{b[0]}: {b[1]} — {b[2]}")


# --- 4. every script either side names is one package.json declares --------------------
# Without this, renaming a script in package.json removes it from BOTH sides at once and the
# parity check above goes green over a suite that no longer runs anywhere.
def check_scripts_resolve(steps):
    count, bad = 0, []
    for directory, target in PAIRS:
        if not package_scripts(directory):
            continue
        for where, (found, unresolved) in (
                ("ci.yml", ci_invocations(steps, directory)),
                (f"`make {target}`", make_invocations(target, directory))):
            count += len(found) + len(unresolved)
            for name in sorted(unresolved):
                bad.append((directory, where, name))
    report("script names", count, "pnpm invocations", bad,
           lambda b: f"{b[0]}: {b[1]} runs `pnpm {b[2]}` — "
                     f"{b[0]}/package.json declares no such script")


print("Pipeline parity (tools/pipeline-parity)")
workflow_steps = read_workflow()
check_workflow_was_read(workflow_steps)
check_every_ci_directory_is_covered(workflow_steps)
check_suites_agree(workflow_steps)
check_scripts_resolve(workflow_steps)

if failures:
    print(f"\nFAILED: {', '.join(failures)}")
    sys.exit(1)
print("\nThe Makefile and CI run the same suites, and each check found something to check.")
