# Security policy

## Supported versions

None, formally. The project is in alpha and there is no released version; fixes land on
`main` and nowhere else. Treat `main` as the only supported version.

## Reporting a vulnerability

Use GitHub's **[private vulnerability reporting](https://github.com/codeministry/leadgen/security/advisories/new)**
on this repository, or mail <marcello@codeministry.de>. Please do not open a public issue
for anything exploitable.

Expect an acknowledgement within a week. This is a one-person project, so there is no
guaranteed fix window — you will get an honest answer about whether and when it will be
addressed rather than a promise that is not kept.

## The threat model, stated plainly

Knowing what this tool does *not* defend against is more useful than a disclosure address,
so here it is.

- **There is no authentication.** `security.auth` accepts exactly one value, `none`, and
  the loader is fatal on any other — because someone writing `basic` and believing the
  write endpoints are protected is the worst failure available here. The write endpoints
  (`POST /api/ingest`, `POST /api/sources/manual/documents`, `PATCH /api/applications/{id}`,
  `PATCH /api/offers/{id}`) are open to anyone who can reach the port.
- **What stands in front of them is the bind address.** `server.address` defaults to
  `127.0.0.1`. Docker Compose overrides it to `0.0.0.0`, because a process bound to
  loopback inside a container is reachable through nothing at all — so **the container is
  only as private as the network you publish its port on.** Do not expose it to the
  internet.
- **It is single-operator.** There is no tenancy, no per-user data and no authorization
  model. Anyone with access to the UI has the operator's access.
- **It holds credentials.** `.env` carries an IMAP password and, optionally, a
  language-model API key. It is gitignored and read at startup; the startup banner masks
  every value it prints by key name, including credentials embedded in a URL. Note that
  `docker compose config` resolves and prints them in clear — that is Compose, not this
  tool.
- **It reads untrusted input by design.** Newsletter HTML, uploaded Markdown and fetched
  portal pages are all attacker-influenceable in principle. Relevant defences: uploads are
  restricted to `.md` by an allowlist, the filename is sanitised and the resolved path is
  checked a second time (`ManualDocumentName`), a directory part in an uploaded name is
  dropped rather than cleaned, the size limit is enforced twice, and an uploaded document
  becomes an offer only after a human confirms what was read from it.
- **A privacy boundary is enforced in code.** Newsletter links are tracking proxies
  carrying the subscriber's mail address as a query parameter. `ProxyLink.unwrap` keeps
  only the `target` and discards the rest of the query — an unrecognised wrapper loses its
  whole query rather than keeping it. A corpus test fails on an `@`, an `email=` or a `%40`
  in any extracted URL.
- **Outbound requests are rate-limited and respect `robots.txt`.** The enrichment stage is
  the only one that leaves the machine.

## Out of scope

- Exposing the application to an untrusted network. It is not built for that, and the
  README says so.
- Anything reachable only by an operator who already has shell access to the host.
