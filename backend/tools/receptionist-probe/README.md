# The Receptionist probe

Three instruments measure the same model, and using the wrong one has cost this project two
sessions. They are not interchangeable.

| | What it answers | Cost | What it cannot do |
|---|---|---|---|
| `LiveReceptionistTest` (level 3, tag `llm`) | Is this case broken at all? | ~1 min, ten cases, one run each | See a rate. At a 4% failure it is green twenty-four runs out of twenty-five |
| `probe.py` (this directory) | Which candidate prompt is better? | ~1 s per trial, pennies for hundreds | State a rate. It exaggerates at both ends |
| `WeekdayResolutionRateTest` (tag `probe`) | How often, really? | ~4 min for fifty conversations, a few cents | Screen many candidates. Too slow to iterate on |

The workflow is: the corpus says something is wrong, the probe finds a candidate fix, the rate test
confirms it, and the number that goes in a commit message is the rate test's.

## Why not just trust the probe

Measured against fifty real conversations on the same two prompts:

| | probe | rate test |
|---|---|---|
| `- MONDAY 2026-09-14` | 29/40 (73%) | 42/50 (84%) |
| `- 2026-09-14 is a MONDAY` | 119/120 (99%) | 48/50 (96%) |

The probe got the direction and the rough size right, which is exactly what a screen is for. It also
once returned 80 of 80 for a prompt a later batch scored 39 of 40 — so a single probe batch is not
even a reliable probe result, let alone a live one. Run at least two.

## Running it

The fixtures are written by a test and are gitignored, because they are outputs:

```bash
cd backend
./gradlew test -PincludeTags=probe --tests '*ProbeFixtureDumpTest'
```

Then, from this directory:

```bash
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../../../.env | cut -d= -f2-)
./probe.py "What have you got free next Monday?" MONDAY 20
```

To try a candidate wording, copy `fixtures/prompt.txt`, edit the copy, and pass `--prompt`. To
confirm the winner:

```bash
cd backend
./gradlew test -PincludeTags=probe --tests '*WeekdayResolutionRateTest'
```

Compare two rates with Fisher's exact test. Over fifty trials, 42 against 48 is p = 0.046; anything
much closer is not a result yet.

## The rule that produced all of this

**Never hand-write the tool JSON.** A simplified two-tool probe scored 5 of 5 on the very prompt the
faithful eight-tool one scored 2 of 5 on. The model fills six arguments at once under a constrained
decoder, and that is where it goes wrong — a probe without the real tools and `strict: true` is
measuring a different system and will tell you a broken prompt is fine.
