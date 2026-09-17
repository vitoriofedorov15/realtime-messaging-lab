# Protocol v2

Canonical source: `protocol/chat.proto`. This version intentionally requires both client and server to be upgraded together. The coursework/v1 client receives `UNSUPPORTED_VERSION` (426), even if its old fields otherwise parse.

## Build and transport

Maven generates Java classes from the canonical file. The React frontend imports the same source with a Vite raw import in `src/chat.ts`; its build bundles the schema and pinned protobuf.js runtime into `client/dist`. Generated assets are ignored by Git. Runtime loading does not use a CDN. This replaces the stage-2 generated script approach; protocol v2 itself is unchanged.

Each binary WebSocket message contains one Protobuf envelope. Inbound fragmented messages are aggregated, with a 2 MiB total message limit. Outbound envelopes are at most **1,572,864 bytes (1.5 MiB)**, measured using their serialized size, including metadata. Text WebSocket messages are not the business protocol.

## Envelope

Requests carry:

- `protocol_version = 2`;
- `request_id`: 1–64 ASCII letters/digits/underscores/hyphens, chosen by the client;
- `type`: JOIN / CHAT / PRIVATE_CHAT;
- exactly one matching `oneof payload` member.

The enum is retained for explicit routing; a mismatched payload is rejected. Protobuf's standard `oneof` decoding is last-member-wins for multiple members on the wire; the application validates the decoded result. Unknown fields remain forward-compatible within the same version, but a missing payload or unknown request type is rejected.

Every response/event includes version 2. A nonempty `request_id` denotes a result for that caller. An empty ID denotes an unsolicited event, except when no valid ID can be recovered from malformed input. IDs provide correlation, **not idempotency**: do not resend a command automatically after disconnect.

## Commands and results

| Request | Caller receives | Other users receive |
|---|---|---|
| JOIN | JOIN_SUCCESS, zero or more nonfinal HISTORY_BATCH messages, one final HISTORY_BATCH; all share the JOIN request ID | USER_JOINED with empty request ID |
| CHAT | NEW_MESSAGE with the caller's request ID | The same NEW_MESSAGE with empty request ID |
| PRIVATE_CHAT | PRIVATE_MESSAGE with request ID, message ID and explicit `target_name` | Only the recipient receives PRIVATE_MESSAGE with empty request ID; self-PM is sent once |
| Invalid command | ERROR or JOIN_ERROR with numeric status, machine `code` and display message | Nothing |

The sender's NEW_MESSAGE/PRIVATE_MESSAGE is the correlated command result, so no separate ACK frame is needed. It acknowledges server handling/enqueueing, not remote delivery or reading. **The stage-2 file store still swallows write failures; durable-commit acknowledgements are not implemented until stage 3.**

Machine error codes: `UNSUPPORTED_VERSION`, `INVALID_REQUEST_ID`, `INVALID_PROTOBUF`, `INVALID_PAYLOAD`, `INVALID_INPUT`, `ALREADY_JOINED`, `NAME_TAKEN`, `ROOM_FULL`, `NOT_JOINED`, `RECIPIENT_OFFLINE`.

## IDs and order

- Each public message gets a UUID `message_id` and a monotonically increasing uint64 `sequence`.
- History and broadcast use the same message ID and sequence.
- Private messages get UUIDs and an explicit recipient but do not consume the public sequence.
- Browser sequence arithmetic uses BigInt from the Protobuf Long string representation, never a floating-point conversion.
- Sequence numbers are recovered from retained public history on normal restart. They are not guaranteed across history deletion/corruption or failed writes.

## Join snapshot

JOIN_SUCCESS contains the sorted list of current usernames (including the joining user), active count and `snapshot_sequence` watermark. It no longer contains history or embedded last-image data in the former v1 fields; those field numbers are reserved.

HISTORY_BATCH contains ordered history records, the same watermark and a completion flag. The batch builder adds records until the full response would exceed 1.5 MiB, then starts another batch. One record with maximum permitted image and avatar fits under that limit. An oversized legacy record rejects JOIN before membership is changed rather than emitting an oversized response.

A separate final batch always exists, including for empty history. If the last image has left the retained history window, that final batch includes it and its message ID. If it is already in history, it is not transmitted twice. Independent last-image persistence remains stage-3 work.

A single room monitor serializes JOIN, chat, PM and leave operations. JOIN captures the snapshot and enqueues all its responses before any later operation can enqueue events. **Every write is scheduled on the destination channel's event loop, even when the caller is already on that loop**, so direct writes cannot overtake earlier cross-thread queued writes. Thus an active joining client sees history completion before later live messages.

The client disables sending until completion, deduplicates by message ID and verifies live public sequence continuity. Its ID cache is bounded to 1000 entries. Reconnect clears the old snapshot and reloads current history; detected ordering errors close the connection and trigger resynchronization. A dropped connection does not provide offline delivery or exactly-once command execution.

This serializes correctness but still holds the room lock during synchronous persistence and history encoding. A 50-record snapshot is split into bounded messages but can still queue many bytes for a slow receiver. Offloading disk I/O and bounding outgoing queues are explicitly deferred to stage 3.

## Stored history compatibility

The original delimited HistoryMessage format keeps its existing field numbers; IDs and sequence are additive fields. On loading old synthetic history, records without IDs/usable sequence values receive them in memory. They become persistent on the next successful history write. No real user history is copied or migrated by the development workflow. Persistence is not yet transactional or crash-safe.
