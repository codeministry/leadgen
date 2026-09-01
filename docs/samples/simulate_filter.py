#!/usr/bin/env python3
"""Simulate the hard filters against the extracted sample offers.

Answers the question that decides whether this is worth building: how many of the
roughly 100 offers a day survive stage 1 and therefore cost an LLM call?
"""
import json, re, unicodedata
from collections import Counter
from pathlib import Path

DATA = Path(__file__).parent / "extracted.json"

# ~120 km radius, approximated as a city list instead of geocoding.
NRW_NEAR = {
    "köln", "koln", "cologne", "düsseldorf", "dusseldorf", "bonn", "aachen", "leverkusen",
    "bergisch gladbach", "neuss", "mönchengladbach", "monchengladbach", "krefeld", "duisburg",
    "essen", "oberhausen", "mülheim", "mulheim", "wuppertal", "solingen", "remscheid",
    "dortmund", "bochum", "gelsenkirchen", "hagen", "siegburg", "troisdorf", "kerpen",
    "bergheim", "düren", "duren", "grevenbroich", "hürth", "hurth", "bedburg", "erftstadt",
    "nordrhein-westfalen", "nrw", "rheinland",
}
REMOTE_TOKENS = ("remote", "homeoffice", "home office", "ortsunabhängig", "deutschlandweit")
FOREIGN = ("ch", "at", "schweiz", "österreich", "osterreich", "zürich", "zurich", "wien",
           "basel", "bern", "genf", "graz", "linz", "salzburg", "luxemburg", "niederlande",
           "amsterdam", "london", "uk", "usa", "spanien", "polen", "warschau", "madrid")

CORE = ("java", "spring boot", "spring", "angular", "typescript", "kubernetes")
ANTI_TITLE = ("sap", "abap", "salesforce", ".net", "c#", "php", "servicenow", "sharepoint",
              "data scientist", "machine learning engineer", "power bi", "scrum master",
              "product owner", "projektleiter", "projektmanager", "tester", "qa ",
              "sap s/4", "oracle apex", "mainframe", "cobol", "embedded", "sps", "ios",
              "android", "flutter", "react native", "netzwerk", "first level", "support")
REMOTE_PCT = re.compile(r"(\d{1,3})\s*%\s*remote", re.I)


def n(s):
    s = unicodedata.normalize("NFKD", s or "").lower()
    return " ".join(re.sub(r"[^a-z0-9äöüß%/\- ]+", " ", s).split())


def check(o):
    """Return the reason for the first rejection, or None if the offer passes."""
    loc = n(o.get("location"))
    title = n(o.get("title"))
    blob = title + " " + n(o.get("description"))
    tags = [n(t) for t in o.get("tags", [])]

    if any(f in loc for f in FOREIGN):
        return "abroad"

    is_remote = any(t in loc for t in REMOTE_TOKENS) or any(t in blob for t in REMOTE_TOKENS)
    pct = REMOTE_PCT.search(blob)
    if pct and int(pct.group(1)) < 80:
        return "remote share < 80%"
    near = any(c in loc for c in NRW_NEAR)
    if not is_remote and not near:
        return "location beyond 120 km"

    if any(a in title for a in ANTI_TITLE):
        return "foreign stack / wrong role"

    if not any(c in " ".join(tags) or c in blob for c in CORE):
        return "no core skill"

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
        print(f"  -{c:>5}  {r:<28} {c / total * 100:5.1f}%")
    print(f"  ={len(survivors):>5}  remaining                    {len(survivors) / total * 100:5.1f}%")
    print(f"\n  that is about {len(survivors) / 14:.0f} LLM assessments per mail")

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
