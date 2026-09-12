import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  EMPTY_STATES,
  RESOURCE_SCREENS,
  UNASSERTED_EMPTY_STATES,
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
 */

const SRC = path.join(__dirname, '..', '..');

function sourceFiles(): string[] {
  return readdirSync(SRC, { recursive: true, encoding: 'utf8' })
    .filter((entry) => entry.endsWith('.tsx') && !entry.endsWith('.test.tsx'))
    .map((entry) => entry.split(path.sep).join('/'))
    .sort();
}

function read(file: string): string {
  return readFileSync(path.join(SRC, file), 'utf8');
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

describe('every component that reads a resource is classified', () => {
  const found = sourceFiles().filter((file) => CALLS_USE_RESOURCE.test(read(file)));
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

  it('makes every hand-rolled exception give a reason and name its test', () => {
    for (const screen of RESOURCE_SCREENS.filter((entry) => entry.states === 'ITS_OWN')) {
      expect(screen.reason ?? '', `${screen.file} is exempt with no reason`).not.toBe('');
      expect(screen.assertedIn, `${screen.file} is exempt and names no test`).toBeDefined();
      expect(() => read(screen.assertedIn!)).not.toThrow();
    }
  });
});

describe('every empty state is classified', () => {
  const found = sourceFiles().filter((file) => read(file).includes('<EmptyState'));
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
