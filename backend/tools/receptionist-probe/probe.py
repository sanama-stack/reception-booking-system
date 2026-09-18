#!/usr/bin/env python3
"""Replays the Receptionist's conversation loop against a live model, many times, cheaply.

WHAT THIS IS FOR
    Choosing between candidate system prompts. It runs one scripted utterance through the real
    loop N times and reports how often the model did the right thing, in about a second per trial
    and a fraction of a cent for the batch.

WHAT THIS IS NOT FOR
    Stating a rate. Measured against fifty real conversations, this probe reported 73% and 99%
    where the live system was 84% and 96%, and once produced an 80-of-80 that a later batch of
    the same prompt corrected to 39 of 40. It exaggerates at both ends and its batches are not
    independent enough to treat as a sample.

    So: use it to SCREEN candidates, then confirm the winner with WeekdayResolutionRateTest, which
    drives the real ConversationService and costs four minutes. Never quote a probe ratio in an
    issue, a commit message or a code comment as though it were the live rate.

    The corpus in LiveReceptionistTest is a third thing again: it runs each case once, so it can
    tell you a case is broken and can never tell you how often.

WHAT IT REPLAYS, AND WHAT IT DOES NOT
    Of ConversationService.runTurn, this file replays the BUDGET and nothing else. The budget is
    the part a measurement rests on, because it decides which tool calls happen at all and what
    this file scores is the arguments of the calls that happened. So it is replayed exactly:

        tool CALLS are counted, never rounds
        the count is checked before each model call AND between the calls of one response
        the turn ends the moment the budget is spent, with the calls already made standing

    Counting rounds instead is the defect this file shipped with. `MAX_ROUNDS = 6` was reconciled
    against `MAX_TOOL_CALLS_PER_TURN = 5` by a comment reading "this only has to be no smaller" —
    which compares two numbers that were never in the same unit. Measured in the self-test below,
    against a model asking for eight tools at a time: the round budget makes 48 calls where the
    application makes 5 and hands off. A probe that outlives the turn it is imitating scores
    searches the application never makes, and it scores them with `all()`.

    Everything else runTurn does is deliberately absent. They are listed because an absence nobody
    wrote down is indistinguishable from a bug:

        the twenty-message context window   one turn, so there is no history to window
        persistence of every message        nothing here is resumable, and nothing here is billed
        the 40-message conversation ceiling
          and the daily cost cap            refusals before the loop, not decisions inside it
        the Offered-Slot check (ADR-0012)   that measures writes; this measures arguments
        the polite hand-off sentence        reported as "(tool ceiling)" rather than as prose

    The model, the endpoint and the ceiling are NOT written in this file. They are read from
    fixtures/loop.json, which ProbeFixtureDumpTest writes out of AiProperties and
    ConversationLimits, so there is no second copy of them to drift.

SELF-TEST
    python3 probe.py --self-test

    No key, no network and no fixtures. It drives the loop against scripted responses and asserts
    the budget — including the one case that separates counting calls from counting rounds, a
    single response asking for eight tools at once. An instrument you have not proven is not an
    instrument, which is the rule stats.py states and the README applies to the probe.

FIXTURES
    All three are written by ProbeFixtureDumpTest and must never be hand-written:

        ./gradlew test -PincludeTags=probe --tests '*ProbeFixtureDumpTest'

    A probe with a simplified tool set scored 5 of 5 on a prompt the faithful one scored 2 of 5
    on. The real eight tools and strict mode are the point.

USAGE
    export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../../../.env | cut -d= -f2-)
    ./probe.py "What have you got free next Monday?" MONDAY 20
    ./probe.py "What have you got free next Monday?" MONDAY 20 --prompt candidate.txt

    The third argument is the weekday every find_available_slots search must land on. To try a
    candidate wording, copy fixtures/prompt.txt, edit it, and pass it with --prompt.
"""

import argparse
import datetime
import functools
import json
import os
import pathlib
import random
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = pathlib.Path(__file__).parent
FIXTURES = HERE / "fixtures"

# Every parameter of the loop that the application decides. Read, never restated — see the header.
REQUIRED_LOOP_FIELDS = ("model", "endpoint", "max_tool_calls_per_turn")


def read_loop(path):
    """The loop's parameters, as the application defines them.

    Every field is required and a missing one is a hard failure rather than a default. A default
    here would be exactly the second definition this file exists not to hold, and it would be the
    worse kind: one that only applies on the day the fixture is stale.
    """
    try:
        loop = json.loads(path.read_text())
    except FileNotFoundError:
        raise SystemExit(
            f"No {path.name}. Run: ./gradlew test -PincludeTags=probe --tests '*ProbeFixtureDumpTest'"
        ) from None

    missing = [field for field in REQUIRED_LOOP_FIELDS if field not in loop]
    if missing:
        raise SystemExit(
            f"{path} is missing {', '.join(missing)}. It is older than the test that writes it — "
            "re-run ProbeFixtureDumpTest."
        )

    ceiling = loop["max_tool_calls_per_turn"]
    # A zero would make every trial return "(tool ceiling)" having searched nothing, and a batch of
    # those reads as a catastrophic prompt rather than as a broken instrument.
    if not isinstance(ceiling, int) or isinstance(ceiling, bool) or ceiling < 1:
        raise SystemExit(f"{path}: max_tool_calls_per_turn is {ceiling!r}, which cannot bound a turn")
    return loop


def post(body, key, endpoint):
    """One call, retrying 429 with backoff. Every other status is raised: a 400 is a probe bug."""
    for attempt in range(8):
        request = urllib.request.Request(
            endpoint,
            data=json.dumps(body).encode(),
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == 7:
                raise
            time.sleep(2**attempt + random.random())
    raise RuntimeError("unreachable")


def stub(name, arguments, service_id):
    """Stands in for the tools.

    Deliberately minimal and deliberately successful: what is under measurement is the arguments
    the model SENDS, so a stub that fails would only measure its own recovery path. If you are
    probing a refusal, return the refusal from the tool under test and leave the rest alone.
    """
    if name == "get_services":
        return {
            "services": [
                {
                    "service_id": service_id,
                    "name": "Haircut",
                    "duration_minutes": 60,
                    "price": "60.00",
                    "currency": "USD",
                }
            ]
        }
    if name == "find_available_slots":
        return {
            "slots": [
                {
                    "starts_at": arguments.get("date_from", "") + "T09:00:00+04:00",
                    "employee_id": "11111111-1111-1111-1111-111111111111",
                    "employee_name": "Nino Beridze",
                }
            ]
        }
    return {"error": {"code": "NOT_SUPPORTED", "message": "not part of this probe"}}


def trial(prompt, tools, utterance, key, loop, service_id, send):
    """One conversation, bounded the way ConversationService.runTurn is bounded.

    Returns the date_from of every availability search, in order, and the last thing said.

    `send` is the transport, so the self-test can drive this exact function without a network.
    It is the seam and not a convenience: a budget proven against a different loop proves nothing.
    """
    messages = [
        {"role": "system", "content": prompt},
        {"role": "user", "content": utterance},
    ]
    searched = []
    ceiling = loop["max_tool_calls_per_turn"]

    # Counts tool CALLS, not iterations, because that is what the application counts. A model
    # asking for more tools in one response than the budget has left sails past a loop that only
    # counts trips round — one iteration, many executions — and the application refuses exactly
    # that, in the comment above its own `toolCallsMade`.
    tool_calls_made = 0

    while True:
        if tool_calls_made >= ceiling:
            # The budget is spent. runTurn answers with TOOL_CEILING_FALLBACK here; this reports
            # the fact rather than the sentence, because the sentence is not under measurement.
            return searched, "(tool ceiling)"

        request = {"model": loop["model"], "messages": messages, "tools": tools, "tool_choice": "auto"}
        reply = send(request, key)["choices"][0]["message"]
        calls = reply.get("tool_calls") or []
        if not calls:
            return searched, (reply.get("content") or "")
        messages.append(reply)

        for call in calls:
            if tool_calls_made >= ceiling:
                # Mid-response, as runTurn does it. The calls already made stand; the rest are
                # simply not made. Executing all of them "because they arrived together" is what
                # makes a ceiling advisory, and an advisory ceiling is not the one being replayed.
                break
            tool_calls_made += 1

            name = call["function"]["name"]
            arguments = json.loads(call["function"]["arguments"] or "{}")
            if name == "find_available_slots":
                searched.append(arguments.get("date_from"))
            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": call["id"],
                    "content": json.dumps(stub(name, arguments, service_id)),
                }
            )


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("utterance", nargs="?", help="what the customer says, as one turn")
    parser.add_argument("weekday", nargs="?", help="the weekday every search must land on, e.g. MONDAY")
    parser.add_argument("trials", nargs="?", type=int, help="how many conversations to run")
    parser.add_argument("--prompt", default=str(FIXTURES / "prompt.txt"), help="a candidate prompt")
    parser.add_argument("--workers", type=int, default=4, help="concurrency; 429s cost time, not accuracy")
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="prove the loop against scripted responses; no key, no network, no fixtures",
    )
    arguments = parser.parse_args()

    if arguments.self_test:
        return selftest()
    if arguments.utterance is None or arguments.weekday is None or arguments.trials is None:
        parser.error("utterance, weekday and trials are required unless --self-test is given")

    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        raise SystemExit("OPENAI_API_KEY is not set")
    if not FIXTURES.exists():
        raise SystemExit(
            "No fixtures. Run: ./gradlew test -PincludeTags=probe --tests '*ProbeFixtureDumpTest'"
        )

    loop = read_loop(FIXTURES / "loop.json")
    prompt = pathlib.Path(arguments.prompt).read_text()
    tools = json.loads((FIXTURES / "tools.json").read_text())
    send = functools.partial(post, endpoint=loop["endpoint"])
    # The id the stubbed get_services hands back, so create_appointment probes can quote it.
    service_id = "11111111-2222-3333-4444-555555555555"
    expected = arguments.weekday.upper()

    with ThreadPoolExecutor(max_workers=arguments.workers) as pool:
        results = list(
            pool.map(
                lambda _: trial(prompt, tools, arguments.utterance, key, loop, service_id, send),
                range(arguments.trials),
            )
        )

    correct = 0
    for dates, said in results:
        if not dates:
            # Not a wrong date. Usually the model asking which service first, which is a legitimate
            # turn the single-turn probe cannot continue - reported, never scored as a resolution.
            print(f"    no search: {said[:120]!r}")
            continue
        if all(datetime.date.fromisoformat(d).strftime("%A").upper() == expected for d in dates):
            correct += 1
        else:
            print(f"    wrong: {dates}")

    searched = sum(1 for dates, _ in results if dates)
    print(f"{correct}/{arguments.trials} correct  ({searched} of {arguments.trials} searched at all)")
    print(f"Model {loop['model']}, ceiling {loop['max_tool_calls_per_turn']} tool calls — from fixtures/loop.json.")
    print("A ratio here SCREENS a candidate. Confirm the winner with WeekdayResolutionRateTest.")
    return 0


# --- the self-test -------------------------------------------------------------------------
# Scripted responses and a counted transport, so every assertion below is about THIS file's loop
# rather than about a re-derivation of it. The separating case is `one response, eight tools`:
# under a round budget it executes all eight, under the application's it executes the budget.


def _search(index, date="2026-09-14"):
    return {
        "id": f"call_{index}",
        "type": "function",
        "function": {"name": "find_available_slots", "arguments": json.dumps({"date_from": date})},
    }


def _asking_for(count):
    """One assistant message requesting `count` searches at once."""
    return {"role": "assistant", "content": None, "tool_calls": [_search(i) for i in range(count)]}


def _transport(reply):
    """Always answers `reply`, and records every body it was given."""

    def send(body, key):
        send.bodies.append(body)
        if len(send.bodies) > 50:
            raise AssertionError("the loop made 50 model calls against a bounded budget")
        return {"choices": [{"message": reply}]}

    send.bodies = []
    return send


def selftest():
    failures = []

    def check(label, got, want):
        ok = got == want
        print(f"  {'ok  ' if ok else 'FAIL'}  {label}: {got!r} (expected {want!r})")
        if not ok:
            failures.append(label)

    loop = {"model": "probe-self-test", "endpoint": "http://example.invalid", "max_tool_calls_per_turn": 5}
    run = functools.partial(trial, "system", [], "utterance", "no-key", loop, "service-id")

    print("The budget counts tool calls, not rounds — one response asking for eight:")
    send = _transport(_asking_for(8))
    searched, said = run(send=send)
    # Measured against the loop as it was: 48 searches and 6 model calls, because the round budget
    # runs all six rounds and executes all eight calls in each. Not 8 — the overrun compounds, and
    # that is why this case is first. The application makes five tool calls and stops.
    check("searches executed", len(searched), 5)
    check("turn ended at the ceiling", said, "(tool ceiling)")
    check("model calls made", len(send.bodies), 1)

    print("The budget holds across rounds, one call at a time:")
    send = _transport(_asking_for(1))
    searched, said = run(send=send)
    check("searches executed", len(searched), 5)
    check("turn ended at the ceiling", said, "(tool ceiling)")
    check("model calls made", len(send.bodies), 5)

    print("A text answer ends the turn, and is not a ceiling:")
    send = _transport({"role": "assistant", "content": "Which service did you want?"})
    searched, said = run(send=send)
    check("searches executed", len(searched), 0)
    check("what was said", said, "Which service did you want?")
    check("model calls made", len(send.bodies), 1)

    print("The model reaching the wire is the fixture's, not this file's:")
    send = _transport({"role": "assistant", "content": "done"})
    run(send=send)
    check("model on the request", send.bodies[0]["model"], "probe-self-test")

    print("A loop.json that cannot bound a turn is refused, not defaulted:")
    for name, bad in (
        ("missing the ceiling", {"model": "m", "endpoint": "e"}),
        ("a zero ceiling", {"model": "m", "endpoint": "e", "max_tool_calls_per_turn": 0}),
        ("a ceiling that is not a number", {"model": "m", "endpoint": "e", "max_tool_calls_per_turn": "five"}),
    ):
        path = FIXTURES / "loop.self-test.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(bad))
        try:
            read_loop(path)
            refused = False
        except SystemExit:
            refused = True
        finally:
            path.unlink()
        check(name, refused, True)

    if failures:
        print(f"\n{len(failures)} check(s) FAILED — this probe is not replaying the application's loop.")
        return 1
    print("\nAll checks passed. The loop is bounded the way ConversationService.runTurn is bounded.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
