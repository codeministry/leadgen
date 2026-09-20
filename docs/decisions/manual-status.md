# Manual status capture

The half of the loop the system cannot observe.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## Manual status capture

`backend/…/application/`. The half of the loop the system cannot observe, and the first
write endpoint in the application.

- **The operator is the authority, so no transition is refused — with one exception.** A
  project can be lost before it was ever answered, and a mistyped status has to be
  correctable without an argument. The eleven states describe the usual path; they are not
  a rule the tool enforces against the person who was actually there. A board that argues
  is a board nobody updates, and it is the only place this state exists.
- **The exception is `PACKAGED`, and it is not about the path being tidy.** The folder an
  application is sent from is built on the way into that state, so a route around it would
  leave a SENT application standing for a document nobody ever made — and a download button
  answering 404. `ApplicationStatus.allowedNext()` therefore lets `NEW` and `SHORTLISTED`
  reach each other, `PACKAGED`, `REJECTED` and `EXPIRED`, and nothing else; the endpoint
  answers 409 for the rest. **Everything after the package stays free**, including the way
  back: the nine other states still answer "all eleven", because a correction there costs
  nothing that has to exist on disk.
- **The rule is served, not mirrored.** `GET /api/applications/transitions` states it and
  the picker greys out what it names, for the same reason the lanes are an endpoint: a
  second copy of the rule in the browser disagrees with the server the first time it moves,
  visibly on the board and invisibly in the code. Nine of the eleven entries are the full
  list, and that is what makes the map cheap to read.
- **Refused and failed are different messages.** Both put the card back, and only one of
  them is worth a second attempt. "The status was not saved" next to a 409 sends the
  operator looking for a problem that is not there.
- **What *is* checked is that the values make sense together.** Moving to a sent state
  with no date gets today rather than a rejection — the operator is recording something
  that already happened, and refusing would cost the status as well.
- **`clearFollowUp` is a `Boolean`, not a `boolean`.** Jackson refuses to map an absent
  value into a primitive, so every request omitting the flag came back 400 — which is
  every request, since the point of a PATCH is that it names one thing. The tri-state is
  also what the field means: leave it, set it, remove it.
- **A closing status drops the follow-up.** A lost project with a standing reminder is how
  a follow-up list stops being read.
- **Every change is an event row.** A single mutable row cannot answer "when did I send
  this" after the second correction. A date-only edit records no event; a status change
  does.
- **The application opens when the offer reaches the shortlist, at `NEW`.** It used to open
  when the package was built, at `PACKAGED`, because that was the first moment there was
  anything for a person to act on. That is what changed: the run now hands over a decision
  rather than a folder, so `OPEN` is its own stage and `ApplicationService.openShortlisted()`
  is one statement. Still idempotent, so a second run never resets a status someone has
  already moved on.
- **An offer taken back out of the archive comes back at `NEW`.** Its package was thrown
  away on the way out unless it had been sent, so leaving it at `PACKAGED` would claim a
  folder that is no longer there — and the guard above only holds on the way *into* the
  package, never after it. The dates of the previous attempt go with the status: a restored
  application keeping a follow-up is a reminder about something that is no longer true, on
  the one list that stops being read when it fills up with those. The event row is what
  makes that lossless, and it is the same log every other correction is written to.
- **Only the decision, never the age pass.** `ArchiveService.run()` reconciles — it brings
  its own rows back the moment the freshness window widens — so resetting a status there
  would be a rule nobody asked for, firing every time a number moves.
- **`isLive()` exempts from `NEW` upwards, and it used to exempt everything but `PACKAGED`.**
  The old wording was right for the old meaning: the packager opened every shortlisted offer
  at `PACKAGED`, so treating that as "in progress" would have exempted the whole shortlist
  from the age rule. They open at `NEW` now and `PACKAGED` is a decision a person made, so
  the exemption moved with the meaning. Left where it was, the rule would archive the offers
  somebody is preparing and keep the ones nobody has looked at.
- **`timestamptz` does not convert straight to an `Instant`.** The Postgres driver throws
  a `DataIntegrityViolationException` naming the whole query rather than the column. Read
  it with `getTimestamp(...).toInstant()`.

