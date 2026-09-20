# The stubbed model endpoint

Two wire formats, one verdict. `openai-chat-completions.json` is the path the deployed
instance takes (`provider: ollama` and `provider: openai-compatible` build the same
client), and `anthropic-messages.json` is the second one, reached with
`SMOKE_LLM_PROVIDER=anthropic`.

**Both bodies are written from the published response shape, not recorded from a live
call.** That is a real difference and it is the limit of this stub: it proves that the
SDK's deserializer can be reached and can build a response object in whatever runtime it
is running in, which is exactly the thing a native image breaks. It does not prove the
shape is current. Replacing either with a scrubbed recording from a real call is an
improvement, and the only step in this suite that needs an API key.

The verdict itself is the contract `ChatClientJudge.reasonsOf` parses: an object with a
`reasons` array, each entry a `factor` from `Judge.JUDGED`, a `points` and a `label`.
`role_fit` has to be among them, because `Judge.answered` reads its presence as "an answer
arrived at all" — a stub without it is indistinguishable from an unreachable endpoint,
which would make step 6 of the suite pass on a failure.
