# Stage 1 — registration and input correctness

Completed 2026-11-13. Application changes are on `stage-1-correctness`; the untouched source baseline is commit `bb5b27d`.

## Changes

- Repeated JOIN returns an error and preserves the original registration. Disconnect releases the original name.
- Username validation permits 1–50 Unicode letters/digits, underscores and hyphens. Case remains significant.
- Public/private text uses the same 25-code-point limit. Too-long text is rejected rather than truncated. Empty/whitespace-only text without an attachment is rejected.
- Server enforces image/avatar byte limits, PNG/JPEG signature/MIME matching and bounded dimensions (4096 per side, 16 million total pixels).
- Image validation reads headers in memory without decoding pixel buffers or touching disk. It is not full file sanitization; valid headers with corrupt pixel data may still pass.
- PM to self produces one response, absent/offline recipients give an error and PM bodies are not logged. The current protocol still has no delivery/read acknowledgement.
- Invalid Protobuf yields an error without unnecessarily closing an otherwise usable connection.
- Departed users' cached avatars are removed; historical messages retain their embedded avatar.
- Preview URLs now use image data rather than an ArrayBuffer assigned to `src`.
- Invalid replacement files clear previous selections. Generation counters prevent stale asynchronous file reads from restoring an old attachment.
- Incoming names/text are rendered with DOM text nodes, image MIME is allowlisted and images are created with DOM attributes rather than HTML interpolation.
- Client counts message-body code points separately from the `@recipient` prefix; it preserves an overlength draft and displays an error.

## Automated verification

Environment: OpenJDK 17.0.19, Maven 3.9.12, macOS arm64.

```sh
mvn -f server/pom.xml clean package
sh scripts/smoke-baseline.sh
```

The actual Maven run used an isolated local dependency cache; JUnit dependencies were obtained from Maven Central.

**JUnit: 13 tests, 0 failures, 0 errors, 0 skipped.** Coverage includes repeated/duplicate JOIN, concurrent contention for one name, capacity and freed slots, Unicode name/text boundaries, pre-JOIN commands, missing payloads, PM isolation/self/absent recipient, valid PNG/JPEG, byte-limit boundaries, invalid MIME/headers/dimensions, avatar reuse and corrupt Protobuf. Each test uses isolated temporary history.

**Real WebSocket probe: 17 checks passed.** Three clients receive public messages; PM reaches sender/recipient but not the observer in the checked scenario; unknown recipient gives 404; public history survives restart; repeated JOIN is rejected and cleans up on disconnect; PM body is absent from logs. The probe stops its own processes.

## Browser verification

A standalone Playwright launch was attempted but could not run in this environment: the expected browser revision was absent and the installed Chromium process aborted under the shell environment. The unverified runner draft is not included in the repository.

The application was instead checked through the available in-app browser against the actual local server:

- Selected a synthetic 1×1 PNG avatar: preview had `naturalWidth = 1` and an image data URL.
- Joined as Alice and received the connected chat state.
- Selected the PNG attachment: preview had `naturalWidth = 1`.
- Sent the attachment; both the avatar and delivered image rendered with `naturalWidth = 1`.
- Entered `@Alice` plus 25 emoji: counter showed `25/25`; send produced one private message.
- Entered 26 ASCII characters: UI showed the length error and retained the full draft.
- Browser error log was empty at the end of this scenario.

The browser check is manual tool-driven verification, not a passing automated browser suite. Multi-browser image delivery, invalid replacement/read-race behavior and adversarial DOM inputs were not exercised in the browser during this stage; the relevant code paths were inspected. Broad UI automation remains planned for stage 4.

## Still open

The protocol schema remains duplicated, JOIN history is unpaged, file persistence is synchronous, and the last-image behavior is unchanged. Reconnect redesign, backpressure, CI, Docker, load tests and dependency auditing are subsequent stages. Image thresholds are code constants until configuration work. Public messages still appear in development logs.

Original coursework files were not modified. No remote repository was created or pushed. No performance improvement is claimed.
