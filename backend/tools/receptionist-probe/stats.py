#!/usr/bin/env python3
"""Exact small-sample statistics for the receptionist instruments.

Written because this helper had been rewritten from scratch in three consecutive sessions, each
time re-deriving the same two functions and re-validating them against the same recorded numbers.

RUN THIS FILE BEFORE TRUSTING IT: `python3 stats.py` re-validates both functions against results
this repository has already recorded and published in issues, and exits non-zero if any of them
has drifted. An instrument you have not proven is not an instrument — the same rule the probe
README applies to the probe.

T18 IS ABOUT THE ARGUMENT ORDER AND IT HAS BITTEN BEFORE. `fisher_one_sided(a..., b...)` tests
whether **b** is better than **a**. Called the other way round it returns a number near 1.000 for
an arm that is dramatically worse, which reads like "no significant difference" and is not.
The validation below asserts both directions for exactly this reason.
"""

import sys
from fractions import Fraction
from math import comb


def fisher_one_sided(a_successes, a_n, b_successes, b_n):
    """One-sided Fisher exact test of the alternative: b's rate is HIGHER than a's.

    Exact rational arithmetic, so there is no floating-point accumulation over the tail.
    """
    total_successes = a_successes + b_successes
    denominator = comb(a_n + b_n, total_successes)
    p = Fraction(0)
    for k in range(b_successes, min(total_successes, b_n) + 1):
        if total_successes - k > a_n:
            continue
        p += Fraction(comb(b_n, k) * comb(a_n, total_successes - k), denominator)
    return float(p)


def clopper_pearson(k, n, confidence=0.95):
    """Exact binomial confidence interval. Returns (low, high) as fractions of 1."""
    alpha = 1 - confidence
    low = 0.0 if k == 0 else _bisect(lambda p: _at_least(k, n, p) - alpha / 2, rising=True)
    high = 1.0 if k == n else _bisect(lambda p: _at_most(k, n, p) - alpha / 2, rising=False)
    return low, high


def _at_least(k, n, p):
    return sum(comb(n, i) * p**i * (1 - p) ** (n - i) for i in range(k, n + 1))


def _at_most(k, n, p):
    return sum(comb(n, i) * p**i * (1 - p) ** (n - i) for i in range(0, k + 1))


def _bisect(f, rising):
    """Bisect f for its root on [0, 1]. `rising` says which way f goes — the upper bound's
    function DECREASES in p, and assuming otherwise silently returns 1.0 or 0.0 for every
    interval, which is how this was first written and how it was caught."""
    low, high = 0.0, 1.0
    for _ in range(200):
        mid = (low + high) / 2
        if (f(mid) < 0) == rising:
            low = mid
        else:
            high = mid
    return (low + high) / 2


def _validate():
    """Against numbers this repository has recorded. Every one is quoted in an issue or handoff."""
    failures = []

    def check(label, got, want, tolerance):
        ok = abs(got - want) <= tolerance
        print(f"  {'ok  ' if ok else 'FAIL'}  {label}: {got:.5g} (recorded {want:.5g})")
        if not ok:
            failures.append(label)

    print("Fisher, one-sided — direction and magnitude:")
    # #15's order fix: 42/50 against 48/50, recorded as p = 0.046.
    check("42/50 vs 48/50", fisher_one_sided(42, 50, 48, 50), 0.046, 0.001)
    # The same pair called backwards. T18: this must NOT look like a small p.
    reversed_p = fisher_one_sided(48, 50, 42, 50)
    print(f"  {'ok  ' if reversed_p > 0.9 else 'FAIL'}  reversed direction: {reversed_p:.5g} (must be > 0.9)")
    if reversed_p <= 0.9:
        failures.append("reversed direction")
    # #17's compounding arms, strict and lenient, recorded in the issue.
    check("13/29 vs 42/47 (strict)", fisher_one_sided(13, 29, 42, 47), 3.9e-05, 1e-06)
    check("24/40 vs 42/47 (lenient)", fisher_one_sided(24, 40, 42, 47), 1.5e-03, 1e-04)

    print("Clopper-Pearson — all four intervals recorded in #17:")
    for k, n, want_low, want_high in [
        (5, 47, 0.035, 0.231),
        (42, 47, 0.769, 0.965),
        (13, 29, 0.264, 0.643),
        (24, 40, 0.433, 0.751),
    ]:
        low, high = clopper_pearson(k, n)
        ok = abs(low - want_low) <= 0.001 and abs(high - want_high) <= 0.001
        print(
            f"  {'ok  ' if ok else 'FAIL'}  {k}/{n}: [{low*100:.1f}%, {high*100:.1f}%] "
            f"(recorded [{want_low*100:.1f}%, {want_high*100:.1f}%])"
        )
        if not ok:
            failures.append(f"{k}/{n}")

    if failures:
        print(f"\n{len(failures)} check(s) FAILED — do not use this helper until it is fixed.")
        return 1
    print("\nAll checks passed. The helper reproduces every recorded value.")
    return 0


if __name__ == "__main__":
    sys.exit(_validate())
