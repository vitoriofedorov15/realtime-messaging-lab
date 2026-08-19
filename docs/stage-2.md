# Stage 2 — canonical protocol and ordered synchronization

Completed 2026-11-13 on `stage-2-protocol`, based on stage 1 commit `3e2431c`.

## Delivered

- One canonical `protocol/chat.proto`; Maven Java generation and generated browser schema use the same source.
- Pinned protobuf.js 8.8.1 with npm lockfile; browser assets are built locally and no longer require CDN access at runtime.
- Protocol version, request correlation, oneof payloads, machine error codes, message UUIDs and public sequence numbers.
- Explicit private-message recipient, participant snapshot and membership updates in the UI.
- History responses capped by serialized byte size (1.5 MiB), with watermark and explicit completion; a separate last-image response when it has left the window.
- Room-wide serialization and consistently queued writes preserve snapshot/live-event order.
- Client deduplication and exact uint64 sequence tracking; sending disabled until history completion.
- Aggregate inbound fragmented binary messages with the existing 2 MiB bound.
- Injectable server room instead of a static global room, and synthetic legacy-history upgrade coverage.
- `scripts/build.sh` builds both sides and runs their unit tests; Maven Wrapper/CI/containers remain stage 5.

## Verification

1. **21 JUnit tests passed**, including all 13 prior regression tests plus 8 protocol tests: version rejection, request/payload validation, correlation versus events, recipient IDs, byte-bounded large history, completion, member snapshot, old history upgrade and fragmentation.
2. **7 Node tests passed**: generated source matches the canonical schema, history/live ordering, duplicate suppression, gap detection, snapshot mismatch, uint64 precision, reconnect reset and bounded ID cache.
3. **Java WebSocket smoke: 17 checks passed**, including normal restart/history restoration under protocol v2.
4. **JavaScript-to-Java protocol integration passed** (`node scripts/protocol-smoke.cjs`):
   - four 1 MiB images with 200 KiB sender avatars replayed through responses each within 1.5 MiB;
   - three new clients joined while another sent 40 messages; the existing reader and all three new clients received public sequences 1–44 exactly once;
   - matching public/PM IDs, request correlation and recipient-only event behavior;
   - incompatible client received a correlated `UNSUPPORTED_VERSION` error.
5. **In-app browser checked with two users**: local generated assets loaded, empty history completed, new entrant saw the existing message once, participant list updated, outgoing PM displayed Bob and Bob received it. Both tab error logs were empty. A previously cached old client was correctly rejected; reloading loaded v2 successfully.
6. Combined `scripts/build.sh` was executed using the isolated Maven/npm caches. JDK 17.0.19, Maven 3.9.12 and Node 24.3.0 on macOS arm64.

The concurrent test is a functional scenario, not a benchmark or proof against every possible interleaving. Four images are padded synthetic PNG payloads, not personal files. Unit tests and integrations are reproducible; the visual check was manual tool-driven browser work.

## Remaining limitations

Synchronous history saving, non-atomic file replacement, swallowed write failures and unbounded slow-client write queues remain for stage 3. A correlated response is not a durable-storage or remote-read guarantee. Last image outside the window is now displayed during the running session, but independent persistence after restart is still missing. No load-test, production or whole-project security-audit claim is made.

Original coursework remains unchanged. No GitHub remote or publication was created.
