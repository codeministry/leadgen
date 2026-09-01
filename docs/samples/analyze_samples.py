#!/usr/bin/env python3
"""Analyse the sample newsletter mails: structure, coverage, duplicates.

A reality check for the extraction and filter rules before a line of Java is
written. Standard library plus BeautifulSoup, nothing else.
"""
import email, glob, json, re, sys, unicodedata
from collections import Counter, defaultdict
from email import policy
from pathlib import Path
from urllib.parse import urlparse, parse_qs, unquote

from bs4 import BeautifulSoup

SAMPLE_DIR = Path(__file__).parent / "emails"

RATE_RE = re.compile(r"(\d{2,4})\s*(?:[,.]\d{2})?\s*(?:€|EUR|Euro)\s*(?:/|pro|je)?\s*(?:h|Std|Stunde|Tag|PT)?", re.I)
REMOTE_RE = re.compile(r"(\d{1,3})\s*%\s*remote|remote\s*(\d{1,3})\s*%", re.I)
FULL_REMOTE_RE = re.compile(r"(100\s*%\s*remote|voll(?:st[äa]ndig)?\s*remote|remote\s*only)", re.I)


def norm(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "").lower()
    s = re.sub(r"\(m/w/d\)|\(w/m/d\)|\(m/f/d\)", " ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return " ".join(s.split())


def clean_url(href: str) -> str:
    """Unwrap the aggregator proxy link and drop the subscriber mail address."""
    if not href:
        return ""
    q = parse_qs(urlparse(href).query)
    if "target" in q:
        return unquote(q["target"][0])
    return re.sub(r"[?&]email=[^&]*", "", href)


def parse_mail(path: Path) -> dict:
    msg = email.message_from_file(path.open(encoding="utf-8", errors="replace"), policy=policy.default)
    html_part = msg.get_body(preferencelist=("html",))
    if html_part is None:
        return {"file": path.name, "offers": [], "error": "no HTML part"}
    soup = BeautifulSoup(html_part.get_content(), "html.parser")

    offers = []
    for card in soup.select("div.job-card"):
        group = card.find_parent("div", class_="tag-group")
        tags = group.select_one("h2.tag-header") if group else None
        meta = [s.get_text(" ", strip=True) for s in card.select("div.job-meta span")]

        def pick(prefix):
            for m in meta:
                if m.startswith(prefix):
                    return m[len(prefix):].strip()
            return None

        desc = card.select_one("div.job-description")
        desc_text = desc.get_text(" ", strip=True) if desc else ""
        link = card.select_one("a.job-link")

        offers.append({
            "title": card.select_one("h3.job-title").get_text(" ", strip=True) if card.select_one("h3.job-title") else "",
            "company": pick("🏢"),
            "location": pick("📍"),
            "posted": pick("📅"),
            "portal": pick("🔗"),
            "tags": [t.strip() for t in tags.get_text(strip=True).split("+")] if tags else [],
            "skills": [p.get_text(strip=True) for p in card.select("span.skill-pill")],
            "description": desc_text,
            "url": clean_url(link["href"] if link and link.has_attr("href") else ""),
        })

    return {
        "file": path.name,
        "from": msg.get("From"),
        "subject": msg.get("Subject"),
        "date": msg.get("Date"),
        "announced": int(m.group(1)) if (m := re.match(r"(\d+)", msg.get("Subject") or "")) else None,
        "offers": offers,
    }


def main() -> int:
    files = sorted(SAMPLE_DIR.glob("*.eml"))
    if not files:
        print(f"no .eml files in {SAMPLE_DIR}", file=sys.stderr)
        return 1

    mails = [parse_mail(f) for f in files]
    all_offers = [o for m in mails for o in m["offers"]]

    print("=" * 72)
    print("EXTRACTION")
    print("=" * 72)
    print(f"{len(files)} mails, {len(all_offers)} offers extracted")
    for m in mails:
        ok = "ok " if m["announced"] == len(m["offers"]) else "DIFF"
        print(f"  {ok} {len(m['offers']):>4} of {str(m['announced']):>4} announced   {m['file']}")

    def coverage(field):
        n = sum(1 for o in all_offers if o.get(field))
        return f"{n:>4}/{len(all_offers)}  {n / len(all_offers) * 100:5.1f}%"

    print("\nField coverage, deterministic (no LLM):")
    for f in ("title", "company", "location", "posted", "portal", "url", "description", "tags"):
        print(f"  {f:<12} {coverage(f)}")

    print("\n" + "=" * 72)
    print("SOURCE PORTALS")
    print("=" * 72)
    for portal, n in Counter(o["portal"] for o in all_offers).most_common():
        print(f"  {n:>5}  {portal}")

    print("\n" + "=" * 72)
    print("DUPLICATES (identical normalized title)")
    print("=" * 72)
    by_title = defaultdict(list)
    for o in all_offers:
        by_title[norm(o["title"])].append(o)
    dupes = {k: v for k, v in by_title.items() if len(v) > 1}
    dup_extra = sum(len(v) - 1 for v in dupes.values())
    print(f"  {len(by_title)} unique titles, {dup_extra} offers are duplicates "
          f"({dup_extra / len(all_offers) * 100:.1f}%)")
    for k, v in sorted(dupes.items(), key=lambda kv: -len(kv[1]))[:8]:
        portals = ", ".join(sorted({x["portal"] or "?" for x in v}))
        print(f"    {len(v)}x  {v[0]['title'][:64]:<64} [{portals}]")

    print("\n" + "=" * 72)
    print("FIELDS STATED IN THE TEXT")
    print("=" * 72)
    with_rate = [o for o in all_offers if RATE_RE.search(o["description"] or "")]
    with_remote = [o for o in all_offers if REMOTE_RE.search((o["title"] or "") + " " + (o["description"] or ""))
                   or FULL_REMOTE_RE.search((o["title"] or "") + " " + (o["description"] or ""))]
    print(f"  hourly rate detectable: {len(with_rate):>4}/{len(all_offers)}  "
          f"{len(with_rate) / len(all_offers) * 100:5.1f}%")
    print(f"  remote share stated:   {len(with_remote):>4}/{len(all_offers)}  "
          f"{len(with_remote) / len(all_offers) * 100:5.1f}%")

    print("\n" + "=" * 72)
    print("LOCATIONS (top 20)")
    print("=" * 72)
    for loc, n in Counter(o["location"] for o in all_offers).most_common(20):
        print(f"  {n:>5}  {loc}")

    print("\n" + "=" * 72)
    print("SEARCH PROFILE TAGS (top 25)")
    print("=" * 72)
    for tag, n in Counter(t for o in all_offers for t in o["tags"]).most_common(25):
        print(f"  {n:>5}  {tag}")

    out = Path(__file__).parent / "extracted.json"
    out.write_text(json.dumps(all_offers, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{len(all_offers)} offers written to {out.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
