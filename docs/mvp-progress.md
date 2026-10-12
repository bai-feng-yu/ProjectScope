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

Other MVP packages remain pending until their implementation and verification are recorded here.
