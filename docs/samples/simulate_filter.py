#!/usr/bin/env python3
"""Simulate the hard filters against the extracted sample offers.

Answers the question that decides whether this is worth building: how many of the
roughly 100 offers a day survive stage 1 and therefore cost an LLM call?

This is the reference implementation. Whatever it does, the Java has to reproduce,
and `docs/SAMPLE-ANALYSIS.md` carries the numbers it prints.

Two defects were found here on 2026-09-01, both silent, both changing the answer:

1. **The umlaut fold.** The old `n()` ran NFKD and then dropped anything outside
   `[a-z0-9äöüß%/\\- ]`, which deletes the combining diaeresis and leaves a space:
   "Köln" became "ko ln", matching neither "köln" nor "koln" in the near list. 35
   offers in Köln and 19 in Düsseldorf — the two cities nearest the home base —
   were discarded as "beyond reach". Folding now removes the combining mark and
   keeps the word whole.

2. **Patterns were compared unnormalised against normalised text.** The text no
   longer contained "." or "#", so ".net" and "c#" matched nothing at all. Folding
   the patterns as well is not the fix on its own: ".net" folds to "net", which
   then matches "Netzwerk" and "Internet". Both sides are folded now *and*
   every list matches on word boundaries. Substrings were wrong in both
   directions: "ch" for Switzerland also matched "Aachen" and "Bochum", rejecting
   127 German offers as abroad, and "essen" for Essen also matched "Hessen",
   accepting six offers from 200 km away.
"""
import json
import re
import unicodedata

import yaml
from collections import Counter
from datetime import date
from pathlib import Path

DATA = Path(__file__).parent / "extracted.json"
# What the run measured, written down for the Java corpus test to assert against. The
# alternative was a table of numbers maintained by hand in four files, and it drifted
# twice in one evening. Regenerating is running this script.
BASELINE = Path(__file__).parent / "filter-baseline.json"

# The stage names are FilterStage's, so the two sides name the same thing; the sentences
# are the enum's own descriptions, kept here only for this script's printout.
STAGE_LABELS = {
    "ABROAD": "abroad",
    "REMOTE_SHARE": "remote share below the minimum",
    "OUT_OF_REACH": "beyond reach, not remote",
    "ROLE_OR_STACK": "foreign stack or wrong role",
    "NO_CORE_SKILL": "no core skill",
    "CONTRACT_FORM": "contract form rejected",
}
# The one place the settings live. Both this script and the Java filter read it, so a
# threshold changed here moves both answers together.
CONFIG = Path(__file__).resolve().parents[2] / "config"

# Roughly a 120 km radius, approximated as a city list rather than geocoding:
# an offer states its location as free text, so a radius would need a dataset, a
# parser and a network call the filter must not need.
# --------------------------------------------------------------------------
# The rules, read from the files the tool itself reads.
#
# Every list below used to be a copy of a YAML block, kept in step by hand. It
# drifted: `max_age_days` sat at 21 here while config/ said 360, and the two
# answers differed by two offers without either file looking wrong. There is one
# set of settings, and this reads it.
#
# The Java filter reads the same two files through its config loader, so
# "the reference and the implementation answer the same question" is now a
# property of the code rather than a promise in a comment.
# --------------------------------------------------------------------------
RULES = yaml.safe_load((CONFIG / "matching-rules.yaml").read_text(encoding="utf-8"))
PROFILE = yaml.safe_load((CONFIG / "skill-profile.yaml").read_text(encoding="utf-8"))

_HARD = RULES["hard_filters"]

NRW_NEAR = set(_HARD["location"]["onsite_cities"])
FOREIGN = tuple(_HARD["location"]["reject_keywords"])
ANTI_TITLE = tuple(_HARD["role"]["rejected_title_keywords"])
CONTRACT_REJECTED = tuple(_HARD["contract"]["rejected"])
MIN_REMOTE_PCT = _HARD["remote"]["min_remote_percent"]

# The tokens that make an offer count as remote, from the `derive_from` rules that
# set 100 % on a keyword. Both the location rule and the title rule, so a title
# saying only "remote" counts the same way the Java filter counts it.
REMOTE_TOKENS = tuple(
    token
    for rule in _HARD["remote"]["derive_from"]
    for token in rule.get("contains_any", ())
)

# The core skills with their aliases: an ad asking for "Springboot", "Spring Data"
# or "k8s" names a core skill, and the eight bare names would answer no.
CORE = tuple(
    name
    for skill in PROFILE["core"]
    for name in (skill["skill"], *skill.get("aliases", ()))
)

REMOTE_PCT = re.compile(r"(\d{1,3})\s*%\s*remote")


def n(s):
    """Fold to lowercase ASCII words: diacritics removed, ß→ss, punctuation to spaces."""
    s = unicodedata.normalize("NFKD", s or "").lower()
    s = "".join(c for c in s if not unicodedata.combining(c)).replace("ß", "ss")
    return " ".join(re.sub(r"[^a-z0-9%]+", " ", s).split())


def word(pattern):
    """A folded keyword or phrase, matched on word boundaries."""
    folded = n(pattern)
    return re.compile(r"(?<![a-z0-9])" + re.escape(folded) + r"(?![a-z0-9])") if folded else None


NEAR_RX = [rx for rx in map(word, NRW_NEAR) if rx]
FOREIGN_RX = [rx for rx in map(word, FOREIGN) if rx]
REMOTE_RX = [rx for rx in map(word, REMOTE_TOKENS) if rx]
CORE_RX = [rx for rx in map(word, CORE) if rx]
ANTI_RX = [rx for rx in map(word, ANTI_TITLE) if rx]
CONTRACT_RX = [(t, word(t)) for t in CONTRACT_REJECTED]



def check(o):
    """Return the reason for the first rejection, or None if the offer passes."""
    loc = n(o.get("location"))
    title = n(o.get("title"))
    blob = title + " " + n(o.get("description"))
    tags = " ".join(n(t) for t in o.get("tags", []))

    if any(rx.search(loc) for rx in FOREIGN_RX):
        return "ABROAD"

    pct = REMOTE_PCT.search(blob)
    if pct and int(pct.group(1)) < MIN_REMOTE_PCT:
        return "REMOTE_SHARE"

    # `min_remote_percent: 0` requires no remote share, so being on site is acceptable —
    # and then it is acceptable anywhere, and the city list stops applying. The same
    # condition sits in HardFilter; both read it from the same file.
    is_remote = any(rx.search(loc) or rx.search(blob) for rx in REMOTE_RX)
    if MIN_REMOTE_PCT > 0 and not is_remote and not any(rx.search(loc) for rx in NEAR_RX):
        return "OUT_OF_REACH"

    if any(rx.search(title) for rx in ANTI_RX):
        return "ROLE_OR_STACK"

    if not any(rx.search(tags) or rx.search(blob) for rx in CORE_RX):
        return "NO_CORE_SKILL"

    if any(rx.search(blob) for _, rx in CONTRACT_RX):
        return "CONTRACT_FORM"

    # Age is deliberately not asked here. "Too old" is not a verdict about an advert, and
    # `max_age_days` now decides whether an offer is on the working list rather than
    # whether it passed — see `archive/ArchiveService.java`.
    return None


def main():
    offers = json.loads(DATA.read_text(encoding="utf-8"))

    reasons = Counter()
    survivors = []
    for o in offers:
        r = check(o)
        if r:
            reasons[r] += 1
        else:
            survivors.append(o)

    total = len(offers)
    print("=" * 72)
    print(f"HARD FILTER: {total} offers")
    print("=" * 72)
    for r, c in reasons.most_common():
        print(f"  -{c:>5}  {STAGE_LABELS.get(r, r):<32} {c / total * 100:5.1f}%")
    print(f"  ={len(survivors):>5}  remaining                    {len(survivors) / total * 100:5.1f}%")
    print(f"  removed {sum(reasons.values())} + remaining {len(survivors)} = {sum(reasons.values()) + len(survivors)}")
    print(f"\n  that is about {len(survivors) / 14:.0f} LLM assessments per mail")

    # Written for the Java corpus test, which asserts against it rather than against a
    # table somebody keeps in step by hand. The settings ride along so a baseline can be
    # read a month later without guessing what produced it.
    BASELINE.write_text(
        json.dumps(
            {
                "measured_on": date.today().isoformat(),
                "settings": {
                    "min_remote_percent": MIN_REMOTE_PCT,
                    "onsite_cities": len(NRW_NEAR),
                    "core_skills": len(CORE),
                },
                "total": total,
                "passed": len(survivors),
                "removed": dict(sorted(reasons.items())),
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"  baseline written to {BASELINE.name}")

    # Duplicates among the survivors
    seen, uniq = set(), []
    for o in survivors:
        k = n(o["title"])
        if k not in seen:
            seen.add(k)
            uniq.append(o)
    print(f"  after deduplication: {len(uniq)} unique offers "
          f"({len(survivors) - len(uniq)} duplicates removed)")

    print("\n" + "=" * 72)
    print("SAMPLE OF SURVIVORS (first 25)")
    print("=" * 72)
    for o in uniq[:25]:
        print(f"  {(o['location'] or '?')[:22]:<22} | {(o['portal'] or '?'):<14} | {o['title'][:70]}")


if __name__ == "__main__":
    main()
