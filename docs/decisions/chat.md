<img src="../brand/leadgen.png" alt="LEADgen / AI" height="28">

# The chat

Asking the corpus in plain words: what the chat is allowed to say, where its numbers come from,
what it may spend, and why it sits where it sits on the screen.

The chat is not a pipeline stage. Nothing in it runs at night, no stage reads a row it writes
(`ChatIsolationTest` fails on a planted reference), and without a chat model the header draws no
button and the tool is the tool it was. Everything below follows from one risk, the one the
advert question in `ask/` was written against: **a model asked about data answers whether or not
the data says anything**, and an invented offer, a wrong count or a cover letter attributed to
the wrong application is worse than no answer, because it looks like one. The claims and their
probes are in spec 019; the wire contract is `chat/ChatEvent` and its six records.

## Grounding by id, not by quote

**The advert question keeps an answer only when it quotes the advert; the chat cannot, so it
checks ids instead.** One advert is a page of text, and "quote the sentence you rely on" is a
check a machine can make. An answer over forty offers would have to quote forty adverts, and a
count has nothing to quote at all. What every statement about an offer or an application does
have is a row, and a row has an id.

So the model marks what it speaks about — `[[offer:42]]`, `[[application:7]]`, as the system
prompt in `leadgen/chat-system.st` demands — and two classes hold it to those marks:

- **`TurnLedger`** records, per turn, every tool call and every id each call returned. An id from
  an earlier turn, from the model's memory or made up looks exactly the same in the text; only
  the ledger tells them apart. It is one per turn and never a bean, because a citation is
  grounded by *this* turn's tools and nothing older.
- **`CitationFilter`** sits in the stream between the model and the screen. A marker becomes a
  numbered link, `[n](cite:offer/42)`, only when the ledger holds the id **and** the row is still
  inside the working set or the archive — for an offer `status = 'PASSED' AND duplicate_of_id IS
  NULL`, archived allowed. Anything else becomes `⟨unverified:42⟩`: text, never a link, drawn with
  a dashed warning underline and a card that says why it is not a link. **One exception: the
  offer the turn's pinned lookup returned** is reachable whatever its status. The reader pinned
  it, the lookup reads any offer, and a filtered-out or duplicate pin whose own citation rendered
  unverified was the drawer contradicting itself. `ChatTurnHardeningTest` holds it with a
  `FILTERED_OUT` pin.

**The server decides, the browser draws.** The filter runs before a byte leaves the machine, so
the browser renders what it is given and makes no grounding decision of its own; a client that
forgot a check cannot turn an unverified id into a link. The alternative — send the raw markers
and the ledger and let the frontend resolve them — puts the one rule this feature rests on into
code that is easier to change and harder to test.

**A marker never leaves half.** The model streams in chunks that split anywhere, including
between the two brackets. From a `[` that could open `[[` the filter holds the text until the
marker closes or proves not to be one; text before it goes out at once. A hold past
`LONGEST_MARKER` (48 characters, longer than any `[[application:ID]]` an 18-digit id makes)
without a close is prose, and a tail still held when the stream ends is flushed as plain text.
The risk the plan named — raw `[[offer:` leaking onto the screen — is covered by a stub that
streams a marker one character at a time.

**Numbers are grounded by where they come from.** The prompt tells the model to take every
count from the statistics tool or from a search's `matched`, never from counting the rows a page
returned, because a search returns a page and not everything that matched. The statistics tool
calls the dashboard's and the analytics screen's own services (§ *Read-only tools*), so a number
in an answer is the number the screen shows for the same window (`ChatToolsTest`, every number
equal).

**Sources are derived, stored once.** `chat_tool_call.returned_ids` keeps what the tools
returned; `chat_turn.citations` (`V34`) keeps the smaller set the answer relied on, in the order
it numbered them, so a reloaded conversation shows the same sources under the same numbers
without re-parsing the Markdown. Nothing stores a source a third time.

## Read-only tools on the screens' own services

Five tools, each a `@Tool` method the model reaches by name — and therefore each with a hint in
`LeadGenRuntimeHints`, because a name computed at runtime is an empty result in a native image:

| Tool | Reads through | Answers |
|---|---|---|
| `search_offers` | `OfferQueryService` with a `ShortlistQuery` | the shortlist's own filters, order and `matched`, plus a came-in window |
| `search_by_meaning` | `QueryEmbedder`, `SemanticFilter.available()`, one `SELECT` | the fifteen nearest primaries over the working set **and** the archive, each flagged |
| `statistics` | `AnalyticsQueryService`, `AnalyticsSummaryQueryService`, `LastRunQueryService` | the dashboard's and the analytics screen's numbers for one window |
| `application` | `ApplicationService`, `CoverLetterService` | one application's state, its event log newest first, its cover letter |
| `profile` | `ConfigRegistry` | the effective skill profile from whichever layer is in force, reference projects included; no name, home base or CV path |

**No SQL of their own, so the chat cannot disagree with a screen.** The obvious build is five
small queries written for the model: shorter, faster, shaped for a prompt. Each one would be a
second definition of the working set, the score band, the archive axis and the duplicate
pointer, and the first time one of those moved the chat would name a list the shortlist does not
show. `search_offers` is held to the shortlist endpoint by `ChatToolsTest`: three filter sets,
identical ids in identical order, identical `matched`. A list the chat names is a list the
shortlist shows.

**The one own `SELECT` is the semantic tool's, and it reuses the rest.** No screen searches the
working set and the archive together by meaning — the shortlist's `semantic=` narrows one side
at a time — and "have we seen anything like this before" is the question this tool exists for,
most of whose answers have aged off the working list. It embeds the question with the same
`QueryEmbedder` the shortlist's search box uses, so the two share one cache, and it asks the
shortlist's `SemanticFilter` whether retrieval is available at all. It never returns a knocked-out offer, an attached duplicate
or a row without a vector from the configured model: `<=>` against a NULL vector is NULL, and
`ORDER BY NULL LIMIT k` hands back k arbitrary rows dressed as the nearest. Fifteen and not
twenty-five, because nearness decays without an edge and a model reading the tail starts finding
patterns in noise; a question that needs more rows is a filter question.

**The came-in window is the chat's alone (ISC-443).** The shortlist has no date, remote or rate
filter, and "this month's Kafka offers" is the most natural question there is. The window was
added to `ShortlistQuery` as two instants that `OfferController` never binds, so the screen is
unchanged, and the SQL counts and orders with it. The alternative — fetch a capped page and
filter it by date afterwards — drops every row past the page and miscounts `matched`, which is
exactly the kind of wrong number this feature exists to avoid. Remote and rate were left to the
text and semantic searches at the operator's call: the corpus rarely states either (a rate in
0.0 % of the newsletter's offers, `docs/SAMPLE-ANALYSIS.md`), and a filter over a field that is
almost always empty answers "none" with confidence.

**Read-only is checked, not promised.** `ChatToolsReadOnlyTest` finds every `@Tool` class by
scan, holds its constructor dependencies to an allowlist of read services with no repository on
it, checks at the bytecode level that the two services that also write are called only on their
read methods, and runs a turn through all five tools with every non-chat table's row count and
checksum unchanged. Actions — starting a run, moving an application, archiving — are the next
spec, as a proposal card the operator clicks; nothing here pre-empts how that one decides.

## Masking what a tool returns

**Every tool result passes `ToolOutputMasker` before the model reads it, in one place.** The
mailbox address never leaves the machine, and a chat is one more way it could: an offer's text,
a profile field or an application note carries it as easily as the configuration does. The
masker replaces the values this instance is actually configured with — each connection's user
and password from `sources.yaml`, every property whose name `Secrets` would mask in the startup
banner, and every property whose value is an e-mail address — collected once per configuration
snapshot, because the configuration reloads. Every other address in an advert stays: a recruiter's
address is what the operator asked about.

**Not every login is a secret, and a secret is masked as a word.** The first masker also took every
property whose last segment named a user — `spring.datasource.username`, the shell's `USER` — and
masked their values case-insensitively inside every advert: with the database login `postgres`,
"PostgreSQL" reached the model as "***QL". Those names are gone from the rule; a login that matters
is a connection's, and an address is caught by its shape. A value that is no address is now masked
only where it stands as a whole word (no letter, digit or underscore on either side), so a secret
that happens to be a common word cannot eat the longer words that contain it. An address is still
masked wherever it appears.

**Values, not patterns, and inside the JSON, not across it.** A pattern for "an e-mail address"
would blank the recruiter along with the mailbox. A textual replace over the serialised result
would let a secret that happens to read `7` turn an id into a mask; so a JSON result is parsed and
only its strings are rewritten, and the model still gets a valid document. Values shorter than
four characters are not masked: a two-letter secret would blank every word it occurs in and
protect nothing a guess would not reveal. `ChatRedactionTest` plants the mailbox address, the IMAP
password and a secret property in an offer, a profile role, an application note and an event
note, runs a turn through every tool, and finds none of them in any body the model stub received.

## Its own budget, beside `llm.budget`

**The chat is the exception to "every stage asks `LlmBudget.take()` first", because it is not a
stage.** The pipeline's ceiling is sized for a nightly pass. Shared with a chat, a long afternoon
of questions would starve the night's judge, and a night that spent the day's allowance would
silence the chat the next morning. So every model call of a turn — the first and each one after a
tool round — asks `chat/ChatBudget.take()` and never `LlmBudget`, and a turn stops after
`chat.max_tool_rounds` rounds.

**Absent is the shipped default, never no ceiling — the one place the chat departs from
`llm.budget`.** A missing `chat.max_calls_per_day` is 200 and a missing `chat.max_tool_rounds` is
6 (`ChatBudget.DEFAULT_CALLS_PER_DAY`, `DEFAULT_TOOL_ROUNDS`), whether the key is left out or the
whole `chat:` block is. Configuration overrides whole files, so an installation whose own
`pipeline.yaml` predates the block reads none at all, and the chat is on by default wherever a
scoring model exists: "absent = no ceiling" handed every such installation an unmetered chat and a
tool loop with no bound, which a model that keeps asking for tools ran for ever on a pool thread.
`0` still means no calls; a ceiling nobody will reach is a large number, not an absent key.

**A turn has a deadline, and it is the turn's, not only the stream's.** `leadgen.chat.turn-timeout`
(15 minutes unless set) is enforced inside `ChatTurnService`: past it the turn is stopped like a stop
request — the model subscription disposed, the row `INCOMPLETE`, `error MODEL` saying it ran out of
time — and its pool thread is handed back. The deadline starts when a pool thread picks the turn up,
so the `SseEmitter` has no container timeout at all: the turn completes the stream itself once its
terminal event is out. It used to be the deadline plus 30 s counted from the request, which a turn
waiting in the queue spent before it had started, and a long enough wait closed the stream before
the turn's `done` or `error` (`ChatStreamQueueWaitTest`). Before that, the emitter's timeout closed
only the stream and the loop outlived it. `ChatTurnDeadlineTest` runs it on a 2 s deadline.

**A table of its own, the pipeline's semantics copied, not shared.** `llm_call_budget` is keyed by
the day alone and has no room for a second counter, so `V33` adds `chat_call_budget` in the same
shape: one upsert guarded by `calls < :limit` checks and increments in one statement, so two turns
cannot both take the last call. `0` is no calls, as for `llm.budget`; absent is the default above.
The round counter is taken once per turn and bound to the ceiling configured then, so a reload in the
middle of a turn does not move the goalposts of the turn already running.

**A refusal ends the turn with its reason.** A refused call is `error BUDGET`, a spent round
count `error ROUNDS`, and the turn is stored `INCOMPLETE` with whatever it had said — never an
answer the model did not finish, presented as one. `ChatBudgetTest` runs it end to end with a
ceiling of two: the third call refused, the stub saw two bodies, and the pipeline's counter read
zero before and after.

**The query vector is paid from the chat's day too.** The semantic tool embeds its question
through the same `QueryEmbedder` and cache as the shortlist's search box, but hands it
`ChatBudget::take` as the budget to ask, where the search box hands `llm.budget`. The first build
shared the pipeline's budget here, and neither `ChatBudgetTest` nor `ChatToolsReadOnlyTest` could
see it, because the test context names no embedding model: a question in the drawer would have
spent, and been refusable by, the nightly run's day, and written `llm_call_budget`, a table outside
the chat's own. `ChatToolsTest` now holds the supplier the tool passes against both counters.
The same gap sat one level down in `search_offers`: a profile topic's paraphrase half embeds the
topic's name through `SemanticFilter.topicNeighbourhood`, which asked `llm.budget`. The tool now
calls `OfferQueryService.shortlist(query, ChatBudget::take)`; the one-argument form is the
screen's and still pays from `llm.budget`. `ChatToolsTest` holds it with both embedder entry points
stubbed to pay what they would pay, so the path the tool takes shows on the counters.

**Defaults of 200 calls a day and 6 rounds a turn.** Both are `${PLACEHOLDERS}` with a default,
unlike `llm.budget.max_calls_per_day`, which is a number in the file, so an instance moves the
chat's ceilings from `.env` without writing a `pipeline.yaml` override. Six rounds leaves room for a
search, a statistics call, an application and a follow-up; neither number is measured yet, and
both move with the measurement of which local model answers tool calls reliably (below).

## The model key and the capability

**`llm.models.chat` follows F43: empty reads `scoring` through `ModelChoice`**, like `content` and
`fields`, and the startup banner says per key which model the chat took and which key decided.
There is no automatic fallback: a question is never retried against another model. With neither
key set, `GET /api/v1/chat/capability` answers `{present: false}`, the header draws no button, and
no chat request leaves the browser (`app-header.spec.ts` counts them across every route). The chat
model has to call tools, which not every model a judge runs on does well; which local model does
it reliably enough is a measurement against a real instance, open in spec 019 as fog, the way the
routing bake-off was measured for the stages.

## Streaming: server-sent events over a `fetch` POST

**`SseEmitter` on the server, `fetch` plus `eventsource-parser` in the browser.** A turn is a
question sent once and an answer that arrives in pieces; server-sent events are exactly that, one
direction, plain HTTP, through every proxy the app already passes. A WebSocket would be a second
protocol and a second security path for a conversation that never talks back mid-answer. The
browser's `EventSource` cannot POST and cannot carry the bearer token in a header, so the stream
is read off a `fetch` body with one pinned parser; `chat.api.spec.ts` feeds it an event split
mid-JSON and mid-UTF-8 across chunks and gets it whole.

**Both streaming endpoints say `X-Accel-Buffering: no` and `Cache-Control: no-cache`.** nginx
buffers a proxied response by default and would deliver the whole answer when the turn ends — a
spinner, then a wall of text — and a cache has no business keeping an event stream. The headers
travel with the response rather than with one proxy's configuration, so every hop in front of the
app is told the same thing. `ChatControllerTest` holds both on ask and on regenerate.

**A heartbeat every 15 s while a turn runs.** A model silent over a long prompt sends nothing, and
a proxy's idle timeout cuts a stream that sends nothing — the chart's nginx keeps 60 s. So while a
turn runs, the stream carries an SSE comment (`:keep-alive`) every `leadgen.chat.heartbeat` (15 s
unless set): every SSE reader ignores a comment, every proxy counts it as traffic. The heartbeats of
all streams share one scheduler thread and stop when the turn ends, when the emitter completes or
times out, or when a write fails because the reader left. A proxy in front still wants its read
timeout (nginx `proxy_read_timeout`) at or above the heartbeat interval — the chart's 60 s is.
`ChatTurnDeadlineTest` holds it with a model silent for several intervals.

**Six events, in a fixed order.** `turn` once, first; `step` around each tool call (running, then
done with a count and a duration); `text` with citations already resolved; `sources` exactly once,
last before `done`; `error` with `MODEL`, `BUDGET` or `ROUNDS` ends the stream and the turn is
`INCOMPLETE` with its partial answer kept. An `error` after a partial answer that cited anything —
out of rounds, out of budget, out of time, the model gone — comes right after a `sources` built from
the same ledger the row stores: the stored turn had its citations all along, and without the event
the live pills pointed at rows the drawer did not have until the conversation was reopened. The
store takes `sources` at any point before the terminal event, so this needed no browser change.

**A NUL in the model's text is dropped before it is streamed.** Postgres refuses U+0000 in `TEXT`,
so one such token used to fail the next flush and end the turn "could not be stored", losing the
unsaved tail. It is removed where the text enters the turn, so the stream and the stored answer
stay the same text; tool labels and arguments were already cleaned in `finish`.

**Deleting a conversation mid-turn is the reader's doing, not a database failure.** The cascade
takes the turn's row, and its terminal write then fails on the tool-call foreign key. After such a
failure the turn asks whether its row still exists; when it does not, it logs one INFO line, writes
nothing more — the `incomplete` fallback would only update no row — and sends one `error`.

**Reachability is asked once per row and turn.** Every marker used to cost the citation filter a
query, even for a row the turn had already cited; the answer is now memoised per row for the turn.
The masker likewise builds its value pattern once per configuration snapshot, not per tool call —
a reload swaps the snapshot object, and the next call rebuilds.

`leadgen.chat.turn-timeout` and `leadgen.chat.heartbeat` bind on `ConfigProperties.Chat`, with their
defaults in one place, like every other `leadgen.*` key.

**Two rounds that both speak are two paragraphs.** A model that narrates ("Let me search.") before
a tool call and answers after it would otherwise run the two into one sentence, in the stream and
in the stored answer. The citation filter is flushed at the end of every model round — a `[` held
across a tool round would join the next round's first words into a marker the model never wrote —
and a round that speaks after an earlier one did starts with a blank line, fed through the filter
so its defusing stage sees the whitespace.

**A database failure is not the model stopping.** Only the model's stream sits inside the
model-failure catch. A `DataAccessException` from a row write or the filter's reachability query is
logged as a storage failure, the turn is ended with one guarded `finish` and no second attempt at
the write that just threw, and `error` keeps the reason `MODEL` — the seam's catch-all — with a
message that says the answer could not be stored.

**A turn runs on a small pool of its own, never on a servlet thread.** An answer takes as long as
the model takes, and the request thread is handed back as soon as the stream is open. Four turns
may run and eight may wait; one operator asks one question at a time, so a queue past that is a
browser gone wrong, refused with 503 rather than left to pile up model calls. A reader that goes
away stops the sending and not the turn: it runs to its end and is stored, so reopening the
conversation shows the answer the closed tab missed.

**The tool loop is driven here, not left to Spring AI.** Every step of it carries a rule of this
tool — the chat budget before each request, the round bound before each round, the masker on each
result, the ledger on each id — and a framework that runs the tools between two calls of its own
leaves nowhere to put them. The model is asked with tool execution left to the caller.

## Stop and regenerate

**Stop cancels the model call, not only the display.** `POST …/turns/{turnId}/stop` sets a flag
the turn reads between chunks and fires a signal that cancels the model request waiting for its
next one, so a local model stops generating rather than finishing into a closed pipe
(`ChatStreamTest`: the stub's connection closed within a second, its last chunk never written).
The partial answer is kept, its citations listed, and the turn stored `STOPPED`; the composer takes
the next question.

**Regenerate adds a turn; it never overwrites one.** The last question is asked again as a new
turn whose `replaces_turn_id` names the one it answers again, and both stay. The replaced answer
is kept for the reader and left out of the history the model reads, because asked again, the model
should not take its own earlier attempt as settled dialogue — but only once an attempt further down
its chain actually answered: a regeneration cut off before it said anything (`INCOMPLETE`, empty
answer) has nothing to stand in its place, and dropping the original made the exchange vanish from
the model's context. **In that history a regenerated answer
stands where the question was first asked**, the ordinal of the first turn of its chain of
replacements, not at its own later ordinal: regenerating T1 after T2 and then asking Q3 sends
`[Q1/A1', Q2/A2, Q3]`, not `[Q2/A2, Q1/A1', Q3]`. Copy puts the answer's Markdown on the
clipboard with its sources as a list of links, so what leaves the drawer carries its grounding with
it.

## Our own shell, the libraries underneath

**The chat UI was meant to be a dependency, and the research said no.** The operator asked for a
chat component off the shelf if one fits. None carried the design on the app's tokens: a
framework-agnostic web component styles through its own shadow DOM, so the tool steps, numbered
citation pills, source cards, AI marks and docked panel would all be pushed through someone else's
styling API; a commercial kit was out; a hosted-chat SDK is not zoneless; and the one
generative-UI framework built for Angular runs its tools in the browser — past the server-side
grounding and masking this feature rests on, which is the one thing that cannot move.

**So the shell is ours and the generic work is not.** `marked` (already in the bundle for the help)
renders the Markdown, DOMPurify sanitises it before it touches the DOM — a model's text is untrusted
input, and a `<script>` in a stubbed answer leaves no element behind — and `eventsource-parser`
reads the stream. Both new dependencies are pinned to an exact version, because a parser and a
sanitiser are the two places a silent minor change would be felt last. Every class carries the
`lg-chat-` prefix, because DaisyUI owns `chat`, `chat-bubble`, `drawer` and `modal`. The
design-system side — the AI colour's third use, the drawer primitive — is in
[frontend-design-system.md](frontend-design-system.md).

## A docked drawer, not a route and not a modal

**A drawer on every screen, chosen by the operator over the recommended `/chat/:id` split view.**
A route would have been the house pattern, and it would have taken the operator away from the
offer they were asking about. The drawer reaches every screen, and opened from an offer's detail it
starts a conversation with that offer pinned. The open conversation is a query parameter —
`?chat=<id>`, `?chat=new`, `?chat=list`, absent means closed; ids are numeric, so the words never
collide — so a reload and a change of screen keep it open and the state stays in the URL, where the
rest of the app keeps it.

**Docked and non-modal from 48rem, the operator's call.** The help drawer is a modal dialog: the
platform gives the backdrop, the inert page and the focus trap, and a reader of the help has nothing
to do on the page behind it. The chat is the opposite case. Following a source is the point of a
source, and a modal would either close on the click or cover the page it opened. So from 48rem the
panel is the shell's second column: the page reflows into the remaining width, there is no backdrop
and no focus trap, Escape with focus inside and ✕ close it, focus moves in on open and back to the
header button on close, and a source navigates beside it with `?chat` unchanged. The cost is the
page squeezed at tablet widths; the page's own responsive rules already cover the narrower box, the
panel steps only at 48rem (36rem wide) and 80rem (56rem, with the conversation rail inside), and the
operator can close it.

**Below 48rem a sheet that folds into a bar.** A phone has no second column, so the chat is a
full-screen modal sheet there, and following a source collapses it into a bar above the bottom
navigation that names the conversation, marks a streaming turn, and expands back on a tap — with
`?chat` still in the URL, so "a change of screen keeps it open" holds literally on every width.

## The schema

`V32__chat.sql` adds `chat_conversation`, `chat_turn` and `chat_tool_call`; `V33` adds
`chat_call_budget`; `V34` adds `chat_turn.citations`. Expand only: no existing table is touched and
no applied migration edited. A conversation's pin is `ON DELETE SET NULL`, so an offer that goes
never takes a conversation with it; turns and tool calls cascade from their conversation, which is
what "deletable one by one" deletes. Who writes each column is in
[DATA-MODEL.md § 5](../DATA-MODEL.md#5-the-chats-four-tables); a turn as a sequence in
[BACKEND-FLOWS.md § 5](../BACKEND-FLOWS.md#5-a-chat-turn).

Rollback is the previous image plus the statements Flyway will not run on its own, and it loses
every stored conversation and nothing else:

```sql
DROP TABLE chat_tool_call, chat_turn, chat_conversation, chat_call_budget;
DELETE FROM flyway_schema_history WHERE version IN ('32', '33', '34');
```

How long conversations are kept is not decided: they are deletable one by one, and a retention rule
would be a claim first.
