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

FIXTURES
    Both are written by ProbeFixtureDumpTest and must never be hand-written:

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
import json
import os
import pathlib
import random
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = pathlib.Path(__file__).parent
FIXTURES = HERE / "fixtures"
ENDPOINT = "https://api.openai.com/v1/chat/completions"

# The model the application defaults to. If AiProperties changes, change this with it.
MODEL = "gpt-4o-mini"

# A tool ceiling, so a model that loops on a refusal cannot bill indefinitely. The application's own
# limit is ConversationLimits.MAX_TOOL_CALLS_PER_TURN; this only has to be no smaller.
MAX_ROUNDS = 6


def post(body, key):
    """One call, retrying 429 with backoff. Every other status is raised: a 400 is a probe bug."""
    for attempt in range(8):
        request = urllib.request.Request(
            ENDPOINT,
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


def trial(prompt, tools, utterance, key, service_id):
    """One conversation. Returns the date_from of every availability search, in order."""
    messages = [
        {"role": "system", "content": prompt},
        {"role": "user", "content": utterance},
    ]
    searched = []
    for _ in range(MAX_ROUNDS):
        reply = post(
            {"model": MODEL, "messages": messages, "tools": tools, "tool_choice": "auto"}, key
        )["choices"][0]["message"]
        calls = reply.get("tool_calls") or []
        if not calls:
            return searched, (reply.get("content") or "")
        messages.append(reply)
        for call in calls:
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
    return searched, "(tool ceiling)"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("utterance", help="what the customer says, as one turn")
    parser.add_argument("weekday", help="the weekday every search must land on, e.g. MONDAY")
    parser.add_argument("trials", type=int, help="how many conversations to run")
    parser.add_argument("--prompt", default=str(FIXTURES / "prompt.txt"), help="a candidate prompt")
    parser.add_argument("--workers", type=int, default=4, help="concurrency; 429s cost time, not accuracy")
    arguments = parser.parse_args()

    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        raise SystemExit("OPENAI_API_KEY is not set")
    if not FIXTURES.exists():
        raise SystemExit(
            "No fixtures. Run: ./gradlew test -PincludeTags=probe --tests '*ProbeFixtureDumpTest'"
        )

    prompt = pathlib.Path(arguments.prompt).read_text()
    tools = json.loads((FIXTURES / "tools.json").read_text())
    # The id the stubbed get_services hands back, so create_appointment probes can quote it.
    service_id = "11111111-2222-3333-4444-555555555555"
    expected = arguments.weekday.upper()

    with ThreadPoolExecutor(max_workers=arguments.workers) as pool:
        results = list(
            pool.map(
                lambda _: trial(prompt, tools, arguments.utterance, key, service_id),
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
    print("A ratio here SCREENS a candidate. Confirm the winner with WeekdayResolutionRateTest.")


if __name__ == "__main__":
    main()
