import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';

/**
 * Which api functions read and which ones write, derived from the module that issues the request.
 *
 * The gate beside this file used to discover screens by one name — `useResource` — and a name is
 * only a reliable question when the thing being looked for has to use it. Nothing requires a screen
 * to read through `useResource`, and nothing at all covered writes, so every component that saves
 * was invisible to a check whose subject is the states a screen owes its user.
 *
 * What *is* required is the client. Every request in the application goes through `api.get`,
 * `api.post`, `api.put`, `api.patch` or `api.delete`, and the domain modules that wrap them are the
 * only things that call it. So the surface is read out of those modules — `businessApi.patch` is a
 * write because the line under it says `api.patch` — and a new endpoint wrapper joins the surface
 * by existing rather than by being remembered.
 *
 * It is a static read of the source, like the rest of this gate. It cannot follow a function
 * assigned through a variable, and it does not try: {@link SURFACE_MODULES} is asserted to be
 * non-empty and every module in it to yield members, so a refactor that moves the wrappers
 * somewhere this cannot see turns the gate red rather than quietly emptying it.
 */

const SRC = path.join(__dirname, '..', '..');

/** The api modules themselves: `lib/<domain>/api.ts`, plus the client and hook they are built on. */
const SURFACE_DIR = 'lib';

export interface ApiMember {
  /** As a caller writes it — `businessApi.patch`. */
  call: string;
  verb: 'get' | 'post' | 'put' | 'patch' | 'delete';
  /** The wrapper module it was read out of, so a module that yields none can be told apart. */
  module: string;
}

function read(file: string): string {
  return readFileSync(path.join(SRC, file), 'utf8');
}

/** `lib/**\/api.ts`, in a stable order. */
export function surfaceModules(): string[] {
  return readdirSync(path.join(SRC, SURFACE_DIR), { recursive: true, encoding: 'utf8' })
    .map((entry) => `${SURFACE_DIR}/${entry.split(path.sep).join('/')}`)
    .filter((entry) => entry.endsWith('/api.ts'))
    .sort();
}

/**
 * Every member of every exported api object, with the verb its body issues.
 *
 * A member is a key in the object literal; its verb is the first `api.<verb>` between that key and
 * the next one. `publicApi.page` fires two `get`s in a `Promise.all` and the first one is enough —
 * a member that both read and wrote would be a member doing two things.
 */
export function apiMembers(): ApiMember[] {
  const members: ApiMember[] = [];

  for (const file of surfaceModules()) {
    const source = read(file);
    const declaration = /export const (\w+) = \{/g;

    for (const object of source.matchAll(declaration)) {
      const objectName = object[1]!;
      const body = objectLiteral(source, object.index + object[0].length - 1);
      const keys = [...body.matchAll(/^ {2}(\w+):/gm)];

      keys.forEach((key, index) => {
        const until = keys[index + 1]?.index ?? body.length;
        const verb = /\bapi\.(get|post|put|patch|delete)\b/.exec(body.slice(key.index, until));
        if (verb) {
          members.push({
            call: `${objectName}.${key[1]!}`,
            verb: verb[1] as ApiMember['verb'],
            module: file,
          });
        }
      });
    }
  }

  return members;
}

/** The text between a `{` and the `}` that closes it. */
function objectLiteral(source: string, open: number): string {
  let depth = 0;
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(open + 1, index);
    }
  }
  throw new Error(`Unclosed object literal at ${open}`);
}

/**
 * Whether a module issues requests at all.
 *
 * Four of the wrappers export only path builders — their screens read through `useResource`, which
 * takes a path — so yielding no members is correct for them and a silence worth telling apart from
 * the other kind: a module that does call the client and that this file could not read. The gate
 * asserts that difference, because a derivation that quietly returns nothing agrees with every
 * catalogue there is.
 */
export function issuesRequests(source: string): boolean {
  return /\bapi\.(?:get|post|put|patch|delete)\s*[<(]/.test(source);
}

const WRITE_VERBS = new Set(['post', 'put', 'patch', 'delete']);

export function writeCalls(): string[] {
  return apiMembers()
    .filter((member) => WRITE_VERBS.has(member.verb))
    .map((member) => member.call)
    .sort();
}

export function readCalls(): string[] {
  return apiMembers()
    .filter((member) => member.verb === 'get')
    .map((member) => member.call)
    .sort();
}

/**
 * Matches any of `calls` used as a call — and not as a mention in prose.
 *
 * `[<(]` rather than `(`, because `api.post<Session>(…)` is a call and `api.post(` does not match
 * it. The first version of this regex missed the login and register forms for exactly that reason,
 * which is the shape of mistake this whole file exists to stop making by hand.
 */
export function callsAnyOf(calls: string[]): RegExp {
  const alternatives = calls.map((call) => call.replace('.', '\\.')).join('|');
  return new RegExp(`\\b(?:${alternatives})\\s*[<(]`);
}

/** The client's own verbs, for the two forms that post without a domain wrapper to go through. */
export const CALLS_CLIENT_WRITE = /\bapi\.(?:post|put|patch|delete)\s*[<(]/;
export const CALLS_CLIENT_READ = /\bapi\.get\s*[<(]/;
