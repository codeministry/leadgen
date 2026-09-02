## What this changes

<!-- One or two sentences. The why matters more than the what; the diff already says the what. -->

## Why

<!-- If it fixes something, what was the failure and how did it show itself? If a number
     moved, say which one and what you measured it against. -->

## Checklist

- [ ] `./gradlew check` passes locally (needs a running Docker for Testcontainers).
- [ ] **Nothing is sent.** No transport, recipient or channel was added — not in code, and
      not in the configuration schema.
- [ ] **Nothing is wired in.** No vendor, portal, provider, model name, host or personal
      datum entered a committed file. New values are `${PLACEHOLDER}`s documented in
      `.env.example`.
- [ ] Everything I wrote is in English — code, comments, config comments, UI strings, tests.
      German only where it is *content* (an ad, a cover letter, a catalog entry).
- [ ] New behaviour has a test beside it, and a comment saying *why* the line exists rather
      than what it does.
- [ ] A user-visible string went into **both** `frontend/public/i18n/en.json` and `de.json`.
- [ ] If a documented number changed, `docs/` and `README.md` say the new one.

## Anything you are unsure about

<!-- Genuinely useful. A question here is cheaper than a review round. -->
