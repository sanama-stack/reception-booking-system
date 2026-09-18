import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  CALLS_CLIENT_READ,
  CALLS_CLIENT_WRITE,
  apiMembers,
  callsAnyOf,
  issuesRequests,
  readCalls,
  surfaceModules,
  writeCalls,
} from './api-surface';
import {
  EMPTY_STATES,
  RESOURCE_SCREENS,
  UNASSERTED_EMPTY_STATES,
  UNASSERTED_WRITE_FAILURES,
  WRITE_SCREENS,
  type EmptyStateScreen,
} from './catalogue';

/**
 * The gate. Discovery here, judgement in `catalogue.ts`, and a failure whenever they disagree.
 *
 * What this can prove and what it cannot is worth being exact about. It cannot watch a screen
 * render — that is what every other test file in this suite does. What it can do is make the
 * *absence* of such a test visible: a screen added tomorrow with no states at all, or with an
 * empty state nobody asserted, fails here with its own path in the message. That is the failure
 * mode a targeted suite actually has — not a wrong assertion, but a screen nobody remembered.
 *
 * **Discovery used to be one name.** A screen was found by calling `useResource`, which meant the
 * gate asked about the read path and could not ask about anything else. Four components read
 * without it — two server pages, the Manage Link flow, and the `/auth/me` every dashboard screen
 * waits on — and twenty-eight write, none of which it had ever seen. A name is only a safe
 * question when the thing being looked for has to use it, and nothing here has to use that one.
 *
 * So the question is asked of the api surface instead (`api-surface.ts`), which is derived from
 * the client every request goes through. `useResource` is still matched, because it is a read the
 * surface cannot see into, and it is now one of several ways in rather than the only one.
 */

const SRC = path.join(__dirname, '..', '..');

/**
 * The application's own source.
 *
 * Not `src/test/` — the suite's own scaffolding is not a screen, and a fixture module once had to
 * be kept out of `.tsx` to avoid being asked to classify itself. Not `lib/api/` or the domain
 * `api.ts` wrappers either: those are the surface this gate derives *from*, and a definition
 * cannot also be an instance of itself.
 */
function sourceFiles(): string[] {
  return readdirSync(SRC, { recursive: true, encoding: 'utf8' })
    .map((entry) => entry.split(path.sep).join('/'))
    .filter((entry) => /\.tsx?$/.test(entry) && !/\.test\.tsx?$/.test(entry))
    .filter((entry) => !entry.startsWith('test/'))
    .filter((entry) => !entry.startsWith('lib/api/') && !/^lib\/[^/]+\/api\.ts$/.test(entry))
    .sort();
}

/** The subset that can render anything. `<EmptyState` is JSX and cannot appear anywhere else. */
function componentFiles(): string[] {
  return sourceFiles().filter((entry) => entry.endsWith('.tsx'));
}

function read(file: string): string {
  return readFileSync(path.join(SRC, file), 'utf8');
}

/**
 * The modules a test file pulls in, as paths under `src/` with the extension dropped.
 *
 * So that a catalogue entry naming its test can be checked against what that test actually
 * renders. A pointer to a file the test no longer imports is worse than no pointer at all: it
 * reads as coverage.
 */
function imports(file: string): Set<string> {
  const directory = path.posix.dirname(file);
  const found = new Set<string>();

  for (const match of read(file).matchAll(/from '([^']+)'/g)) {
    const specifier = match[1]!;
    if (specifier.startsWith('@/')) found.add(specifier.slice(2));
    else if (specifier.startsWith('.')) {
      found.add(path.posix.normalize(path.posix.join(directory, specifier)));
    }
  }

  return found;
}

function asserts(test: string, file: string): boolean {
  return imports(test).has(file.replace(/\.tsx?$/, ''));
}

/** Matches a call — `useResource<T>(…)` or `useResource(…)` — and not a mention in prose. */
const CALLS_USE_RESOURCE = /useResource[<(]/;

/**
 * The literal titles an `EmptyState` in this file renders.
 *
 * Deliberately only the literal ones. A `title={reason.title}` is copy this file does not choose,
 * and the catalogue says where such copy comes from instead.
 */
function literalTitles(source: string): string[] {
  return [...source.matchAll(/<EmptyState[\s\S]{0,400}?title="([^"]+)"/g)].map(
    (match) => match[1]!,
  );
}

describe('the api surface this gate derives from', () => {
  /**
   * The derivation has to be able to fail loudly. A regex that stopped matching would otherwise
   * empty every list below it, and two lists that are both empty agree.
   */
  it('finds the wrapper modules and reads members out of them', () => {
    expect(surfaceModules().length).toBeGreaterThan(5);
    expect(writeCalls().length).toBeGreaterThan(20);
    expect(readCalls().length).toBeGreaterThan(10);
  });

  /**
   * The silence that must not pass for agreement.
   *
   * Four wrappers export only path builders, because their screens read through `useResource`,
   * which takes a path — those yielding nothing is correct. A module that calls the client and
   * still yields nothing is the derivation having gone blind, and every list below it would then
   * be empty and agree with a catalogue that is not.
   */
  it('reads members out of every wrapper that actually issues a request', () => {
    const members = apiMembers();
    for (const wrapper of surfaceModules()) {
      if (!issuesRequests(read(wrapper))) continue;
      expect(
        members.filter((member) => member.module === wrapper).length,
        `${wrapper} calls the client and this gate read no members out of it`,
      ).toBeGreaterThan(0);
    }
  });
});

describe('every component that reads a resource is classified', () => {
  const readsThroughSurface = callsAnyOf(readCalls());
  const found = sourceFiles().filter((file) => {
    const source = read(file);
    return (
      CALLS_USE_RESOURCE.test(source) ||
      readsThroughSurface.test(source) ||
      CALLS_CLIENT_READ.test(source)
    );
  });
  const catalogued = RESOURCE_SCREENS.map((screen) => screen.file);

  it('lists every one of them, and nothing that is not one', () => {
    // Both directions. A new screen must be classified before the build goes green, and a line
    // whose file has stopped reading a resource cannot sit here making the catalogue look
    // complete.
    expect(found.filter((file) => !catalogued.includes(file))).toEqual([]);
    expect(catalogued.filter((file) => !found.includes(file))).toEqual([]);
  });

  it('has no duplicate entries', () => {
    expect(new Set(catalogued).size).toBe(catalogued.length);
  });

  it('is telling the truth about which of them use the shared gate', () => {
    for (const screen of RESOURCE_SCREENS) {
      const usesGate = read(screen.file).includes('ResourceGate');

      if (screen.states === 'RESOURCE_GATE') {
        expect(
          usesGate,
          `${screen.file} is catalogued as gated and does not use ResourceGate`,
        ).toBe(true);
      } else {
        // The other direction matters just as much: a screen that has since adopted the gate
        // should lose its exemption rather than keep a reason that is no longer true.
        expect(usesGate, `${screen.file} is catalogued as hand-rolled and now uses the gate`).toBe(
          false,
        );
      }
    }
  });

  it('makes every exception give a reason, and every kind of exception give its own evidence', () => {
    for (const screen of RESOURCE_SCREENS) {
      if (screen.states === 'RESOURCE_GATE') continue;
      expect(screen.reason ?? '', `${screen.file} is exempt with no reason`).not.toBe('');

      if (screen.states === 'ITS_OWN') {
        expect(screen.assertedIn, `${screen.file} is exempt and names no test`).toBeDefined();
        expect(() => read(screen.assertedIn!)).not.toThrow();
        expect(
          asserts(screen.assertedIn!, screen.file),
          `${screen.assertedIn} does not import ${screen.file}`,
        ).toBe(true);
      }

      if (screen.states === 'ELSEWHERE') {
        expect(
          screen.shownBy,
          `${screen.file} says its states render elsewhere and not where`,
        ).toBeDefined();
        expect(() => read(screen.shownBy!)).not.toThrow();
      }
    }
  });
});

describe('every empty state is classified', () => {
  const found = componentFiles().filter((file) => read(file).includes('<EmptyState'));
  const catalogued = EMPTY_STATES.map((screen) => screen.file);

  it('lists every one of them, and nothing that is not one', () => {
    expect(found.filter((file) => !catalogued.includes(file))).toEqual([]);
    expect(catalogued.filter((file) => !found.includes(file))).toEqual([]);
  });

  it('has an assertion, a source for its copy, or a written reason — never nothing', () => {
    for (const screen of EMPTY_STATES) {
      const classified =
        (screen.assertedIn?.length ?? 0) > 0 || !!screen.dynamic || !!screen.notYet;
      expect(classified, `${screen.file} is catalogued with no classification at all`).toBe(true);
    }
  });

  /**
   * The assertion that gives the pointers their value. Every literal title in the source must
   * appear in one of the test files the entry names — so a title added to a screen, or quietly
   * reworded, turns this red until its test says the new words.
   */
  it('asserts every literal title of every screen that claims to be asserted', () => {
    for (const screen of EMPTY_STATES) {
      if (!screen.assertedIn?.length) continue;

      const tests = screen.assertedIn.map(read).join('\n');
      for (const title of literalTitles(read(screen.file))) {
        expect(
          tests.includes(title),
          `“${title}” is rendered by ${screen.file} and asserted by none of ${screen.assertedIn.join(', ')}`,
        ).toBe(true);
      }
    }
  });

  it('makes a screen with no literal title of its own say where its copy comes from', () => {
    for (const screen of EMPTY_STATES) {
      if (literalTitles(read(screen.file)).length > 0) continue;
      expect(
        screen.dynamic ?? screen.notYet ?? '',
        `${screen.file} renders no literal title and does not say where its copy comes from`,
      ).not.toBe('');
    }
  });

  /**
   * The ratchet. Not an achievement — a debt, counted where it cannot be overlooked.
   */
  it('carries exactly the number of unasserted empty states it admits to', () => {
    const unasserted = EMPTY_STATES.filter((screen: EmptyStateScreen) => !!screen.notYet);

    expect(
      unasserted.length,
      `unasserted: ${unasserted.map((screen) => screen.file).join(', ')}`,
    ).toBe(UNASSERTED_EMPTY_STATES);

    for (const screen of unasserted) {
      expect(screen.notYet, `${screen.file} is carried with an empty reason`).not.toBe('');
      expect(screen.assertedIn ?? [], `${screen.file} is carried and asserted at once`).toEqual([]);
    }
  });
});

describe('every component that writes is classified', () => {
  const writesThroughSurface = callsAnyOf(writeCalls());
  const found = sourceFiles().filter((file) => {
    const source = read(file);
    return writesThroughSurface.test(source) || CALLS_CLIENT_WRITE.test(source);
  });
  const catalogued = WRITE_SCREENS.map((screen) => screen.file);

  it('lists every one of them, and nothing that is not one', () => {
    expect(found.filter((file) => !catalogued.includes(file))).toEqual([]);
    expect(catalogued.filter((file) => !found.includes(file))).toEqual([]);
  });

  it('has no duplicate entries', () => {
    expect(new Set(catalogued).size).toBe(catalogued.length);
  });

  it('makes every one of them say what the user is shown when the write is refused', () => {
    for (const screen of WRITE_SCREENS) {
      expect(screen.failure ?? '', `${screen.file} says nothing about its failure`).not.toBe('');
    }
  });

  /**
   * Asserted or admitted, never both and never neither. The pointer is the half that rots: a test
   * can stop rendering a component and go on being named here, and a named test is read as proof.
   */
  it('either names tests that really render it, or admits that none does', () => {
    for (const screen of WRITE_SCREENS) {
      const named = screen.assertedIn ?? [];
      expect(
        named.length > 0 !== !!screen.notYet,
        `${screen.file} is both asserted and carried, or neither`,
      ).toBe(true);

      for (const test of named) {
        expect(() => read(test)).not.toThrow();
        expect(asserts(test, screen.file), `${test} does not import ${screen.file}`).toBe(true);
      }
    }
  });

  /** The ratchet, the same shape as the one above it. */
  it('carries exactly the number of unasserted write failures it admits to', () => {
    const unasserted = WRITE_SCREENS.filter((screen) => !!screen.notYet);

    expect(
      unasserted.length,
      `unasserted: ${unasserted.map((screen) => screen.file).join(', ')}`,
    ).toBe(UNASSERTED_WRITE_FAILURES);

    for (const screen of unasserted) {
      expect(screen.notYet, `${screen.file} is carried with an empty reason`).not.toBe('');
    }
  });
});
