# Known issues at the source baseline

These are tracked findings, not completed improvements. Runtime verification is distinguished from source inspection.

| ID | Finding | Evidence | Planned stage |
|---|---|---|---|
| B01 | Repeated JOIN on one channel leaves the former name occupied after disconnect. | Reproduced against freshly compiled ChatRoom by the baseline probe. | 1 |
| B02 | Server does not enforce attachment/avatar size and JPEG/PNG validity; the frame limit is not a file validator. | Source inspection of WebSocketHandler. | 1 |
| B03 | Public text is silently truncated; private text lacks the same server limit. Browser maxlength counts the private-address prefix too. | Source inspection of handler and client. | 1 |
| B04 | Private message to self is written to the same channel twice. | Source inspection of handlePrivateChat. | 1 |
| B05 | FileReader ArrayBuffer is assigned directly to img.src for previews. | Source inspection of both file pickers; not browser-tested. | 1 |
| B06 | Untrusted MIME is interpolated into HTML attributes when rendering images/avatars. | Source inspection; exploit not executed. | 1 |
| B07 | Protocol is duplicated manually in JavaScript and .proto; PM has no recipient field. | Source inspection. | 2 |
| B08 | JOIN sends all history in one response, potentially tens of MiB with images. | Source inspection; no large-history measurement yet. | 2 |
| B09 | Each public message rewrites the entire history synchronously under the room lock. | Source inspection; no latency measurement yet. | 3 |
| B10 | Last image outside the 50-message window is ignored by the client and is not independently persisted. | Source inspection. | 3 |
| B11 | Historical avatars can accumulate by username without cleanup; queued outgoing writes have no explicit backpressure policy. | Source inspection. | 3 |
| B12 | Private-message contents are printed to server logs. | Source inspection. | 1 |
| B13 | No automated browser coverage, CI, wrapper or container setup. Client runtime depends on a CDN. | Repository inventory. | 4–5 |

The current implementation does not authenticate a person's identity, provide offline private delivery or establish a measured capacity of 100 concurrent users. The constant limiting joined users is not a load-test result.

Dependencies have not yet undergone a vulnerability audit. No performance or reliability improvement is claimed at this stage.

## Stage 1 status (2026-11-13)

The table above preserves the original findings. B01–B06 and B12 are now addressed: repeated JOIN, server-side text/file checks, self-PM duplication, previews, safe DOM rendering and removal of PM bodies from logs. See `stage-1.md` for verification scope.

B11 is partially addressed: departed users' avatar caches are removed (history has its own copy). Slow-client backpressure is still outstanding. B13 is partially addressed: 13 JUnit tests and a server smoke probe exist, and the UI was manually checked in the in-app browser. An automated browser runner, CI, wrapper and containers remain outstanding.

B07–B10 remain open. The server still writes history synchronously, sends it in a single JOIN response and lacks end-to-end delivery acknowledgements. Header checks are not full image sanitization. No load-test or dependency-security claims are made.


## Stage 2 status (2026-11-13)

B07 and B08 are addressed: canonical schema, explicit PM recipient and byte-bounded history responses. B10 is partially addressed: the running server sends an outside-window last image separately and the client displays it; independent last-image persistence still remains. B13 now includes 21 JUnit tests, 7 Node tests, a Java/JavaScript integration scenario and locally built browser scripts without a CDN runtime dependency. CI, wrapper and containers remain outstanding. See `stage-2.md` and `protocol.md` for the exact contracts and limits.

B09 and the slow-client portion of B11 are still open. Responses currently acknowledge server handling, not durable commits. The history migration is tested on synthetic records only.
