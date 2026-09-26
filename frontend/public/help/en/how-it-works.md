The app turns a stream of project offers into a short list worth reading and, when you decide to apply, into a ready application package. It never applies for you: it reads, sorts, judges and prepares, and every step that goes out into the world is yours.

## One run, five phases

A run starts when you press **Run ingest**, or on a schedule. It works through the same stages in the same order every time, grouped into five phases.

<!-- diagram: run-phases -->

**Read.** Every enabled source is read once: a newsletter mailbox, a folder of Markdown files, or any other configured source. Each document is split into single offers, and each offer gets its title, link, location, rate and the rest of what the source states. The app remembers what it has already seen, so a second run does not create a second copy.

**Sort.** First, deduplication: the same project, advertised by two agencies or on two portals, becomes one entry that names every place it appeared. Offers that match exactly are folded right away; offers that only read alike are compared by meaning and marked as possible duplicates. Then the hard filter runs. Its stages are fixed rules — abroad, remote share, out of reach, role or stack, no core skill, contract form — and an offer stops at the first one it fails. This is **rules before model**: the filter is deterministic and free, it removes most of the offers, and only what survives costs a model anything. Finally, offers whose ad has aged past the freshness window leave the working list for the archive, from where you can always bring them back.

**Understand.** Only the survivors are looked at more closely. The app fetches the full advert from the portal — the one stage that leaves your machine — because a newsletter carries a teaser, not the ad. It then separates the advert from the portal's furniture (navigation, forms, legal notices, agency signatures), so that what is judged is the ad itself, and reads the start date, the duration and the application deadline out of the text.

**Judge.** Every offer gets a score out of 100. Rule factors do what rules can decide on their own: skill overlap against your profile, the rate, seniority, how much of the engagement the ad describes, industry. A language model is asked only what rules cannot answer: whether the role really fits you, plus a few penalties. The total is a share of what the ad made attainable, so an ad is judged on what it says, not punished for what it leaves out. The score places the offer in one of the bands you know from the dashboard: **shortlisted**, **to review** or **discarded**. Without a model the run still completes; the offers keep their rule factors and are marked **unscored**. After scoring, each advert is indexed by meaning, which is what **Find related** searches.

**Hand over.** What reached the shortlist opens an application on the board, at **New**. The run also writes the day's digest, a readable summary of what came in.

## Where a model takes part

A model helps in a handful of places, and the Rules screen marks each one as an **AI step**: comparing offers by meaning for deduplication and for the related search, telling the advert from the portal's furniture, reading dates and durations out of free text, judging the role fit, and drafting the cover letter. Every one of these runs on a local model on your own machine, so it is free and nothing is sent to a third party. A hosted model is used only when you choose one for a specific run; the app never falls back to one on its own. And because the rules come first, the app still works without any model at all, only less sharply: nothing is skipped, the offers are just not ranked.

Telling the advert from its furniture and reading the dates are small, bounded questions, so each can be given a smaller model of its own; left empty, the model that judges answers them too. The select beside **Run ingest** picks the judge for one run and changes neither of the two, and they read the adverts again only when a model of their own is set and changes.

By default a run works on one advert at a time. The configuration can give it a width: deduplication, telling the advert from its furniture, reading the dates, judging and indexing then work on several adverts at once, and fetching the adverts can too, still inside the portal's rate limit and the cap per run. A width moves the clock and never the bill, because the day's model budget counts requests, not time — and it only helps when the model runtime can answer that many requests at once; otherwise the extra ones just wait in line.

<!-- screenshot: run-phases-rail -->

## How the parts work together

<!-- diagram: parts -->

The sources deliver the raw material. The pipeline does the reading, sorting, understanding and judging, and asks the local model where it needs one. Everything it learns lands in one database: the offers, their scores and reasons, the applications and their history. The screens read that database, and the only things you write back are your own decisions: an archive, a status, a note, a letter. The configuration — sources, rules, profile, cover-letter text — is read by the pipeline and shown on the Sources and Rules screens, but never edited from the app.

## The path of an application

<!-- diagram: application-states -->

An application opens at **New** when its offer reaches the shortlist; **Shortlisted** marks one you want to pursue. Moving it to **Packaged** builds the package: a folder with the cover letter, the fixed CV in the language of the ad, and the reference projects that match. That is the one step the board will not let you skip, because a sent application without a package would stand for a document nobody ever made.

From there on, everything is your record. You send the application from your own mail client and move it to **Sent**, then on through **Replied**, **Interview** and **Offer**, and finally **Won**, **Lost**, **Rejected** or **Expired**. Nothing is ever sent from the app, which is why it cannot know any of this unless you record it — and why no move after the package is refused, so a mistake is always one click to correct.

Archiving an offer discards its package, unless the application was ever sent; restoring it brings the application back at **New**. That way "Packaged" and "there is a folder" always mean the same thing.
