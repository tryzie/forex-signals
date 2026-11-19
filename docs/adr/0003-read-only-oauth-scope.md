# ADR 0003: Read-only cTrader OAuth scope, and no order code at all

- Status: Accepted (Phase 0, 2026-11-18)

## Decision
SIGNALS ONLY is enforced in three independent layers:
1. **Broker:** the token must have the `accounts` scope. At connect, the adapter checks
   `permissionScope == SCOPE_VIEW` and refuses anything else. It also refuses any account not flagged as demo.
2. **Code generation:** the order, amend, close, cancel and margin-call-update request messages are
   removed from the vendored `OpenApiMessages.proto`, so no Java class for them exists.
3. **Build:** `ReadOnlyGuardTest` fails the build if hand-written code mentions those messages or their
   payload types, if the protos define them again, if generated classes exist for them, or if any
   source or config asks for the `trading` scope.

## Consequences
Execution, if ever wanted, needs a deliberate, separately approved phase that undoes all three layers.
