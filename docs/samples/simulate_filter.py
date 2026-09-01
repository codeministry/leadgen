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
from collections import Counter
from datetime import date
from pathlib import Path

DATA = Path(__file__).parent / "extracted.json"

# Roughly a 120 km radius, approximated as a city list rather than geocoding:
# an offer states its location as free text, so a radius would need a dataset, a
# parser and a network call the filter must not need.
NRW_NEAR = {
    "köln", "cologne", "düsseldorf", "bonn", "aachen", "leverkusen",
    "bergisch gladbach", "neuss", "mönchengladbach", "krefeld", "duisburg",
    "essen", "oberhausen", "mülheim", "wuppertal", "solingen", "remscheid",
    "dortmund", "bochum", "gelsenkirchen", "hagen", "siegburg", "troisdorf", "kerpen",
    "bergheim", "düren", "grevenbroich", "hürth", "bedburg", "erftstadt",
    "nordrhein-westfalen", "nrw", "rheinland",
}
REMOTE_TOKENS = ("remote", "homeoffice", "home office", "ortsunabhängig", "deutschlandweit")
FOREIGN = ("schweiz", "österreich", "zürich", "wien",
           "basel", "bern", "genf", "graz", "linz", "salzburg", "luxemburg", "niederlande",
           "amsterdam", "london", "uk", "usa", "spanien", "polen", "warschau", "madrid")

# The operator's core skills and their aliases, mirrored from
# config/skill-profile.yaml. The Java filter reads that file directly; this
# list exists so the reference and the implementation answer the same
# question. A hand-picked six used to sit here and missed 12 offers naming
# Spring Cloud, OpenAPI or k8s.
CORE = (
    "java",
    "spring boot",
    "angular",
    "typescript",
    "rest / api-design",
    "microservices / systemarchitektur",
    "kubernetes",
    "spring cloud",
    "java 17",
    "java 21",
    "jdk",
    "jakarta ee",
    "j2ee",
    "jee",
    "spring",
    "springboot",
    "spring-boot",
    "spring framework",
    "spring mvc",
    "spring data",
    "spring security",
    "angular 2+",
    "angular 17",
    "angular 18",
    "angular 19",
    "angular 20",
    "angularjs-migration",
    "ts",
    "openapi",
    "swagger",
    "api-first",
    "rest-api",
    "restful",
    "http-api",
    "microservice",
    "service-architektur",
    "softwarearchitektur",
    "solution architect",
    "domain driven design",
    "ddd",
    "k8s",
    "k3s",
    "microk8s",
    "rancher",
    "openshift",
    "helm",
    "argocd",
    "gitops",
    "netflix oss",
    "eureka",
    "gateway",
    "config server",
)
ANTI_TITLE = ("sap", "abap", "salesforce", ".net", "c#", "php", "servicenow", "sharepoint",
              "data scientist", "machine learning engineer", "power bi", "scrum master",
              "product owner", "projektleiter", "projektmanager", "tester", "qa",
              "oracle apex", "mainframe", "cobol", "embedded", "sps", "ios",
              "android", "flutter", "react native", "netzwerk", "first level", "support")
# Contract forms the tool never wants. `ANÜ` is why these match on word boundaries:
# as a substring it also hits "Planung", "Manufacturing" and "manuellen" — 23 false
# rejections in the sample corpus from one three-letter token.
CONTRACT_REJECTED = ("Festanstellung", "Arbeitnehmerüberlassung", "ANÜ",
                     "Personalvermittlung", "Praktikum", "Werkstudent")
MAX_AGE_DAYS = 21

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


def posted_on(o):
    raw = o.get("posted") or ""
    m = re.search(r"(\d{2})\.(\d{2})\.(\d{4})", raw)
    if m:
        return date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
    m = re.search(r"(\d{4})-(\d{2})-(\d{2})", raw)
    return date(*map(int, m.groups())) if m else None


def check(o, newest=None):
    """Return the reason for the first rejection, or None if the offer passes."""
    loc = n(o.get("location"))
    title = n(o.get("title"))
    blob = title + " " + n(o.get("description"))
    tags = " ".join(n(t) for t in o.get("tags", []))

    if any(rx.search(loc) for rx in FOREIGN_RX):
        return "abroad"

    pct = REMOTE_PCT.search(blob)
    if pct and int(pct.group(1)) < 80:
        return "remote share below 80%"

    is_remote = any(rx.search(loc) or rx.search(blob) for rx in REMOTE_RX)
    if not is_remote and not any(rx.search(loc) for rx in NEAR_RX):
        return "beyond reach, not remote"

    if any(rx.search(title) for rx in ANTI_RX):
        return "foreign stack / wrong role"

    if not any(rx.search(tags) or rx.search(blob) for rx in CORE_RX):
        return "no core skill"

    if any(rx.search(blob) for _, rx in CONTRACT_RX):
        return "contract form rejected"

    posted = posted_on(o)
    if newest and posted and (newest - posted).days > MAX_AGE_DAYS:
        return "older than %d days" % MAX_AGE_DAYS

    return None


def main():
    offers = json.loads(DATA.read_text(encoding="utf-8"))
    dates = [d for d in map(posted_on, offers) if d]
    newest = max(dates) if dates else None

    reasons = Counter()
    survivors = []
    for o in offers:
        r = check(o, newest)
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
    print(f"  removed {sum(reasons.values())} + remaining {len(survivors)} = {sum(reasons.values()) + len(survivors)}")
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
