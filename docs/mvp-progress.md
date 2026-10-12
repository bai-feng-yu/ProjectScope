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

Other MVP packages remain pending until their implementation and verification are recorded here.
