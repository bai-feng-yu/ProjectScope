# MVP implementation log

This log records verified implementation, not planned completion. Feature commits use
the existing `feat:` / `fix:` / `test:` convention. See the lifecycle plan for scope.

## M1 — Atomic completion and request idempotency

- Final Step, Run, Attempt and events share one transaction.
- POST /runs and /tasks accept Idempotency-Key (1–200 characters).
- Keys are scoped to the authenticated user and create-run operation; both routes share the same scope.
- Replaying identical input returns the original Run with its current status; changed input returns 409.
- Keys are retained with no automatic expiry. Legacy clients without a header remain non-idempotent.
- Real MySQL tests cover concurrent submissions, conflicting input and rollback of both completion and key reservation.

## M2 — Minimum admission policy

- Production ToolCall admits only READ_ONLY tools named in the server allowlist.
- app_user.enabled and disabled_tool provide live operator-controlled revocation.
- HTTP requests, model-step creation, tool execution and owned snapshot reads recheck the relevant boundary.
- Revocation does not block durable failure cleanup. No user-account connector or model-selected credentials are enabled.
- New Conversation/Memory/Schedule resources must apply the same ownership checks when added.

## M3 — Persistent conversation API

- Conversations and messages are user-owned; cross-user lookups return 404.
- Message submission requires an Idempotency-Key; replay never calls the model twice.
- User messages survive model failure; successful replies are published atomically.
- Only one synchronous turn is pending per conversation. A new submission expires pending turns older than three minutes; there is no automatic replay.
- Model I/O holds no database transaction. The minimal UI will be delivered with the research workflow.
- Real MySQL tests cover isolation, idempotency, failed replies and stale pending turns.

## M4 — Personal Memory

- Best-effort JSON extraction creates at most three candidates from each new user message, with source IDs and FACT/PREFERENCE/INFERENCE labels.
- Only explicitly confirmed memories enter cross-session context; same-topic conflicts require explicit replacement.
- Corrections preserve version history; optimistic version checks reject stale writes. Deletion redacts every stored version of the topic.
- A processed-source ledger prevents replay from recreating deleted memory. Disabling memory prevents extraction and retrieval; existing confirmed records are retained until explicitly deleted.
- Retrieval uses topic matching plus recent confirmed memories (up to eight), not embeddings. User corrections take precedence over memory.
- Extraction failure never rolls back a saved reply. No background extraction retries are promised.

## M5 — Durable conversation / Run bridge

- POST /conversations/{id}/research requires Idempotency-Key and atomically creates a Run plus its linked user message.
- Research submissions snapshot the relevant confirmed memory; only the owning user can submit/read the conversation.
- The database scheduler picks up committed Runs. A separate retryable publisher appends terminal results with unique reply_to deduplication.
- A publication outage delays the chat reply but cannot lose the Run or duplicate its reply. Existing Run snapshots/events remain queryable.
- Research messages are accepted submissions, not synchronous pending replies; their runId identifies the live execution status.
- MySQL fault-injection verifies message-write failure also rolls back Run creation.

## M6 — Brave web search Tool (backend)

- web_search is a READ_ONLY AgentTool and uses the same allowlist, Step/Tool persistence and Run events as existing tools.
- Enable with BRAVE_SEARCH_ENABLED=true and BRAVE_SEARCH_API_KEY in the server environment. It is disabled by default.
- The destination is fixed to Brave; redirects and automatic retries are disabled. Pool/connect/read limits are 2/3/8 seconds.
- Returns at most five HTTP(S) source links with bounded title/snippet lengths; response bodies are capped at 256 KiB. No arbitrary webpage fetching.
- Missing credentials, rate limits and unavailable search return explicit error codes without leaking provider error bodies or headers.
- Local HTTP contract tests cover encoding, response parsing, unsafe-link rejection, missing keys, 429 and redirect rejection.
- Real provider/model validation still requires operator credentials; local tests do not claim live Brave validation. The UI remains pending.
- Provider contract: https://api-dashboard.search.brave.com/app/documentation/web-search

## M7 — Resource limits and basic cancellation

- Existing step count limits remain. A Run receives a database-clock deadline when claimed (default 300 seconds).
- Every lease-fenced write rejects an expired deadline; the recovery scan terminalizes the Run even if heartbeat is otherwise live.
- Per-user running concurrency defaults to two. Claims serialize on the user row; excess work stays PENDING and the scheduler skips blocked users.
- POST /runs/{id}/cancel is owned and idempotent. Cancellation shares the Run lock with final completion; the first terminal transition wins.
- Pending tools fail and already-started uncertain tools remain UNKNOWN. In-flight HTTP calls may continue until their transport timeout, but cannot persist a late success.
- Cancellation is not a rollback of external effects. High-risk external write tools remain prohibited.

## M8 — Simple weekly research

- POST /schedules creates an explicitly authorized weekly research task with conversationId, content and firstFireAt (ISO instant); Idempotency-Key is required.
- GET /schedules and PUT /schedules/{id}/pause are user-scoped. Pausing stops future triggers, not an already-submitted Run.
- Weekly wall time is Asia/Shanghai; stored timestamps are UTC. Missed weeks coalesce into one current trigger, then advance to the next weekly slot.
- Scheduler row locking and atomic Run creation/slot advancement prevent duplicate triggers. Previous unfinished Runs or busy users cause the slot to be skipped.
- Results use the same conversation/Run publication path. Resume may trigger one overdue slot; it does not replay every missed week.
- No arbitrary cron, automatic retries, multihost capacity guarantee or proactive external writes are included.

## M3 / M6 — Minimal local web interface

- Spring Boot serves a responsive buildless interface at /. Only the HTML/CSS/JS shell is public; all data APIs still require JWT authentication.
- Supports conversation creation, chat, candidate confirmation/replacement, memory correction/deletion, research submission, authenticated SSE, cancellation and weekly schedules.
- JWT is retained in page memory only. Disconnect clears displayed user data; late responses from older sessions cannot repopulate it.
- User/model content renders as text, not HTML. Message and schedule retries reuse their request key while the request payload is unchanged.
- Static serving, JavaScript syntax and security-filter checks are automated. Visual/browser interaction verification depends on an available browser connection.

Other MVP packages remain pending until their implementation and verification are recorded here.
