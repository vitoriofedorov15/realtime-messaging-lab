# Stage 0 — source baseline

Verified on 2026-11-13 (Europe/Vienna).

## Source preservation

Seven source/build-definition files were copied from the coursework folder: four Java classes, one .proto, one HTML client and pom.xml. SHA-256 comparison confirmed that each copied file equals the original and that the original files remain unchanged.

No original JAR, generated Java code, compiled classes, chat history, report, video-link document or IDE configuration was copied. The Git repository is new and local; no remote or publication has been configured.

## Build

- Host: macOS arm64.
- JDK used for build and probe: Homebrew OpenJDK 17.0.19.
- Maven: 3.9.12.
- Command: `mvn -B -o -Dmaven.repo.local=<isolated-cache> -f server/pom.xml clean package`.
- The isolated cache was copied from the machine's existing Maven dependency cache. No original project build output was used.
- Result: **BUILD SUCCESS**, Protobuf Java regenerated, executable JAR built.
- Maven reported **No tests to run**. The smoke probe below is separate.

This verifies a source build with cached dependencies, not a download into an empty Maven cache, Docker build or other operating systems.

## Runtime smoke check

Command: `sh scripts/smoke-baseline.sh`, with `JAVA_HOME` set to JDK 17.

The probe launches the built JAR with isolated temporary synthetic history, uses Java's real WebSocket client and stops its own server afterward. A first attempt was blocked by the environment's network sandbox; after local network access was allowed the same probe completed successfully.

Observed results:

```text
PASS: Alice joins
PASS: Bob joins
PASS: Observer joins
PASS: Duplicate name rejected
PASS: Public message delivered
PASS: Public message delivered
PASS: Public message delivered
PASS: PM echoed to sender
PASS: PM delivered to recipient
PASS: Observer received barrier without private message
PASS: Unknown recipient returns 404
PASS: Two public messages survive restart; PM absent
PASS: Restored history content matches
PASS: KNOWN DEFECT reproduced: repeated JOIN leaves stale name
BASELINE SMOKE PASSED (including one expected known defect)
```

There are 13 positive baseline checks and one expected-defect reproduction. The privacy check is a short scenario with an observer message as a barrier; it is not exhaustive concurrency verification. The restart check covers ordinary completed writes, not crash recovery or durability under power loss.

## Not verified yet

Browser rendering, attachments over the network, reconnect UI, all limit boundaries, concurrent JOIN races, 100-client load, slow clients, corrupted history, failed writes and dependency security are deferred to their implementation stages.

No benchmark has been run and no performance baseline is claimed. The source snapshot and smoke probe establish a reproducible functional starting point for subsequent stages.

## Local Git identity

No user Git name/email was configured in the environment. The baseline commit uses a command-scoped automation identity (`Codex <codex@localhost>`); no global Git settings or user identity were invented or changed. Before later publication the author identity can be chosen explicitly.
