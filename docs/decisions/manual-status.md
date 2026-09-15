# Manual status capture

The half of the loop the system cannot observe.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## Manual status capture

`backend/…/application/`. The half of the loop the system cannot observe, and the first
write endpoint in the application.

- **The operator is the authority, so no transition is refused.** A project can be lost
  before it was ever answered, and a mistyped status has to be correctable without an
  argument. The eleven states describe the usual path; they are not a rule the tool
  enforces against the person who was actually there. A board that argues is a board
  nobody updates, and it is the only place this state exists.
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
- **The application opens when the package is built**, because that is the first moment
  there is anything for a person to act on, and opening is idempotent so a second run
  never resets a status someone has already moved on.
- **`timestamptz` does not convert straight to an `Instant`.** The Postgres driver throws
  a `DataIntegrityViolationException` naming the whole query rather than the column. Read
  it with `getTimestamp(...).toInstant()`.

