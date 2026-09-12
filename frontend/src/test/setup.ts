import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

/**
 * `next/navigation`, stubbed once for the whole suite.
 *
 * Every dashboard screen is a client component under the App Router, and several read the router,
 * the path or the query. Outside Next there is no router context, so `useRouter()` throws and the
 * screen never renders far enough to show the state under test. This is the smallest thing that
 * lets a real screen render — it records what a screen asked for rather than pretending to
 * navigate.
 */
vi.mock('next/navigation', () => ({
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => '/',
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({}),
  redirect: vi.fn(),
  notFound: vi.fn(),
}));

// Testing Library cleans up automatically only when `afterEach` is a global. It is not here —
// `globals` is off, so every test file names what it imports — so the teardown is wired by hand.
// Without it, a screen rendered by one test is still in the document for the next one, and a
// query that should find nothing finds the previous test's markup.
/**
 * `<dialog>`, minimally.
 *
 * jsdom implements the element but none of its modal behaviour, so `showModal()` is simply absent
 * and any component built on a native dialog throws on mount. The drawer and the confirm dialog
 * both are — deliberately, because the focus trap, the inert background and the Escape key are
 * things a browser supplies and this application declined to re-implement.
 *
 * **This shim is not those things**, and no test here should claim them: it opens and closes, and
 * that is all. What a browser actually does with a modal dialog is the E2E flow's to prove, not
 * this file's.
 */
const dialog = globalThis.HTMLDialogElement?.prototype;
if (dialog && typeof dialog.showModal !== 'function') {
  dialog.showModal = function showModal(this: HTMLDialogElement) {
    this.open = true;
  };
  dialog.show = function show(this: HTMLDialogElement) {
    this.open = true;
  };
  dialog.close = function close(this: HTMLDialogElement, returnValue?: string) {
    this.open = false;
    if (returnValue !== undefined) this.returnValue = returnValue;
    this.dispatchEvent(new Event('close'));
  };
}

afterEach(cleanup);

// `serve()` in the harness installs a `fetch`. Left in place it would answer the next test file's
// requests from the last case of the previous one.
afterEach(() => vi.unstubAllGlobals());
