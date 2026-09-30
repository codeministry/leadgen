<img src="../brand/leadgen.png" alt="leadGEN / AI" height="28">

# The MCP server

leadgen answers MCP clients itself, at `/mcp`: what it serves, why it moved in from a separate
aggregator, what every answer passes on its way out, and how a client finds out where to get a
token. The claims and their probes are in spec 023.

## Why it lives in the backend

**The tools read the services the screens read, in the same process.** Until spec 023 a separate
server, codeministry-mcp, served six `leadgen_*` tools by calling leadgen's REST API with a
client-credentials token. That was a second copy of the read surface to keep in step (an API path
change broke it more than once), a second token plumbing across two realms, and one more hop that
could fail for reasons that had nothing to do with the question asked. Now the tools call
`OfferQueryService`, `ApplicationService` and the analytics services directly, and
`McpToolsTest.theToolsOpenNoConnectionOfTheirOwn` records every destination the JVM resolves while
all ten run: nothing but the test's own `/mcp` and the database.

codeministry-mcp keeps `codeministry_status` and nothing of leadgen.

**Spring AI's MCP server, WebMVC, Streamable HTTP, sync.** The same starter family leadgen already
uses for its models, on the servlet stack it already runs; tools are `@McpTool` methods on four
components in the `mcp` package. SSE-only transport is deprecated in the protocol, and a sync
server fits a service whose every read is a blocking JDBC call.

## Ten tools: six moved, four added

**The six carry codeministry-mcp's contract unchanged**: names, parameter names, descriptions,
annotations and the shape of every answer. A client configured against the old server works
against this one after a URL change. `McpToolContractTest` holds it: `tools/list` equals
`baseline-tools.json`, and each answer is compared with an answer recorded from codeministry-mcp on
the demo corpus, by JSON type and key, recursively. Values are not compared, because the recording
ran on a different corpus; a renamed field, a changed type or a dropped key fails.

The answers are typed records rather than the maps the old server assembled from JSON. Two of
codeministry-mcp's habits survive on purpose: a field with no value is left out rather than sent as
`null` (a screenful of `"rateEur": null` is noise a model pays for), and `truncated` and
`nextCursor` appear only when there is more.

Three things this server does differently from the old one, each because the old answer misled.
`leadgen_get_offer` leaves out the advert unless asked, and that means the blocks the detail
reads it in as well as `fullText`: the same text in another shape is the same pages again. It
never names the package's path on this server, which no client can use; the board says
`hasPackage`. And `leadgen_list_applications` refuses a status that does not exist with the valid
ones, where the old server answered an empty board a model would report as a fact. The inherited
description's own example, `'APPLIED'`, was such a status; it reads `'SENT'` now, the one change
to the six descriptions, made on purpose and pinned in `baseline-tools.json` like the rest.
`leadgen_funnel_stats` with a section builds that section alone (`AnalyticsQueryService.section`,
whose `SECTIONS` the tool names in its error), not the whole analytics view.

**The four added are the chat's own tools**: `leadgen_semantic_search`, `leadgen_statistics`,
`leadgen_application` and `leadgen_profile` call the chat's tool classes, so the two surfaces answer
the same arguments the same way (`McpChatToolsTest` compares them as JSON). They run without a chat
turn: no conversation, no pinned context, no turn ledger, no call against the chat's daily ceiling.
The one tool that asks a model, the search by meaning, pays its embedding from `llm.budget`, the
ceiling the shortlist's own search and `leadgen_search_offers` already draw on.

**Every tool is read-only and says so.** `readOnlyHint` on all ten, and `McpToolsReadOnlyTest`
compares every table's rows and the packages directory before and after calling each of them. The
package itself stays a download from leadgen's UI and is not reachable here.

**A page on a rebound name is refused, on `/mcp` and everywhere else.** Under `security.auth:
none` a page on a name its author points at this machine (DNS rebinding) reaches the API as a
same-origin request, reads included, with no preflight in the way; the MCP transport spec asks a
server to refuse it, and the REST write paths need the same. Such a request always carries the
author's name as its Host, and on anything but a plain GET as its Origin too. `RebindingGuard`,
installed in the `none` chain only, refuses a Host that is a dotted name nobody configured, and
it reads every Host the client sent: the raw header and each X-Forwarded-Host, never only the one
Tomcat settled on, because with forwarded headers on a same-origin page can hand the valve a
local name of its own. That holds while no proxy in front rewrites the Host and passes a client's
X-Forwarded-Host on, so nginx and the dev server's proxy both overwrite it with the Host the
browser sent. Loopback, IP literals and single-label names (a Compose service, a pod IP)
pass as a Host; an Origin, being a page, passes when it is local, configured, or the very host the
request was sent to (the UI opened by a LAN address), never some other IP or service name. A real host
name under `none` is listed in `ALLOWED_HOSTS`, a port or a trailing dot there is forgiven, and
the health question stays open. A first version
put the SDK's Origin validator on `/mcp` alone: it left `/api` open to the same attack and, with
its localhost-only list, refused a browser-based MCP client under `oidc` that held a valid token.
Under `oidc` there is no guard at all; the token is the check.

## Every answer passes the masker

**The configured mailbox address and every value the startup banner masks never leave in an MCP
answer**, whichever tool returned it. `MaskedResults` wraps the handler of every tool in the list the
MCP server is built from, so an eleventh tool is masked without anyone remembering to; it masks the
serialised answer, text and structured content, never selected fields, so a new field cannot slip
past either. A handler that throws answers as an error result with the masked message, the way the
chat does, rather than as a protocol error carrying the exception's text.

The masker is the chat's `ToolOutputMasker`, unchanged. An MCP answer goes to whatever model the
client runs, which is the same exposure the chat's tool results have.

## Authentication: the resource server that is already there

**`/mcp` follows `security.auth` and adds no chain of its own.** Under `none` it is open like every
endpoint; under `oidc` it wants a bearer token that passes the issuer and audience checks every
other endpoint applies. A client-credentials client of the realm (`leadgen-mcp` today) gets a token
for `aud: leadgen-api` and is served.

**A client finds the issuer through RFC 9728.** Spring Security 7.1 ships the protected resource
metadata filter; leadgen uses it rather than a controller of its own, adds the issuer as the one
authorization server, and says `tls_client_certificate_bound_access_tokens: false`, which Spring
defaults to `true` and which these plain bearer tokens are not. A 401 on `/mcp` names the metadata in
`WWW-Authenticate … resource_metadata=`, which is how the MCP authorization spec expects a client to
start.

**The resource is `…/mcp`, by the path-suffixed form.** RFC 9728 derives a resource's metadata URL
by inserting `/.well-known/oauth-protected-resource` before its path, so
`/.well-known/oauth-protected-resource/mcp` names `https://host/mcp` by itself, and the bare
document names the whole server, where its other 401s point. A client rejects metadata whose
resource is not the one it derived the URL from (§ 3.3), so the two are never mixed.

**The scheme and host come from the proxy.** Derived from the request alone they are whatever the
last hop spoke: behind a TLS-terminating ingress, `http://`. `server.forward-headers-strategy:
native` has Tomcat read `X-Forwarded-Proto` and `-Host`, and only from a private or loopback peer,
which the compose nginx and the ingress are and a client on the internet is not. Once
`X-Forwarded-Proto` is there, Tomcat takes the port from `X-Forwarded-Port` and otherwise the
scheme's default, so nginx sends the port the browser's Host named for these two paths (a `map`
on `$http_host`), and the dev server's proxy sets `xfwd`. It changes how every request reads its
own URL, and nothing in leadgen reads the peer address for a decision. One more effect had to be
switched off: knowing it sits behind https, Spring Security would send HSTS for a year,
subdomains included, a commitment that belongs to whoever terminates TLS, so both chains disable
it. `security.oidc.resource` (`OIDC_RESOURCE`) stays as the explicit answer
where a proxy chain gets it wrong anyway; set, it replaces the MCP endpoint's resource and the
401's pointer, never the server's.

## The proxy in front

The compose stack's nginx and the chart's ingress both send `/mcp` (exact) and
`/.well-known/oauth-protected-resource` (prefix) to the api. Without that the SPA fallback answers
both with `index.html` and a 200, which a client reads as metadata that does not parse. nginx does
not buffer `/mcp`, for the reason it does not buffer the chat: Streamable HTTP answers a call as
server-sent events and keeps a GET stream open.

## Reflection, and the native image

The MCP server finds its tools by annotation and calls them by reflection, and each answer goes to
JSON through its record's accessors. `LeadGenRuntimeHints` registers the four tool classes' `@McpTool`
methods and binds their result types, including the records behind the sealed `FunnelStats`, which
no signature names. `LeadGenRuntimeHintsTest` scans for `@McpTool` and for every record in the `mcp`
package, so a new tool or record without a hint fails there, and `backend/smoke/smoke.sh` calls all
ten against a finished image and refuses an answer that serialised as `{}`.

The native image itself does not start yet, with or without MCP: stage 2 of `native-image.md` was
never finished, and the configuration model is the first thing it trips on. Spec 023 hinted the one
field that stopped it first (`ConfigProperties$Chat`), proves MCP on the image that ships, and
leaves the native start to a spec of its own.
