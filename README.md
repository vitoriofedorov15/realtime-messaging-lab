# Realtime Messaging Lab

A coursework WebSocket chat built with Java, Netty and Protocol Buffers, being developed into a reproducible engineering portfolio project.

**Status: stage 1 — input validation and correctness fixes.** Registration, private-message edge cases and image previews are fixed, with regression tests. Persistence, protocol synchronization and production hardening remain planned. This is not a production service.

## Current implementation

- Java 17 / Netty server, browser client using plain JavaScript.
- Binary WebSocket messages encoded with Protocol Buffers.
- Named users, public and private messages, image attachments and avatars.
- File-based public message history.

See [stage 1 verification](docs/stage-1.md), the historical [baseline](docs/baseline.md) and [known issues](docs/known-issues.md).

## Build

Requirements: JDK 17 and Maven (verified with JDK 17.0.19 and Maven 3.9.12). Set `JAVA_HOME` to your JDK 17 installation if another Java version is the default. A first build needs access to Maven Central; subsequent builds can use a populated local Maven cache.

From the repository root:

```sh
mvn -f server/pom.xml clean package
```

This generates Java protocol classes from `server/src/main/proto/chat.proto` and builds the executable JAR. No prebuilt JAR is committed. The build runs 13 JUnit regression tests. To run only these checks: `mvn -f server/pom.xml test`.

## Run locally

From the repository root:

```sh
mkdir -p runtime
cd runtime
java -jar ../server/target/chat-server-1.0-SNAPSHOT-jar-with-dependencies.jar
```

Open `client/index.html` in a browser, enter a name and join. Open another tab with a different name to exchange messages. The browser currently loads protobuf.js from a CDN, so browser startup requires internet access.

- WebSocket endpoint: `ws://localhost:8080/ws` (currently hardcoded).
- Stop the server with Ctrl+C.
- History is written to `chat_history.bin` in the server's working directory, here `runtime/` (ignored by Git).
- The original server binds the port on all interfaces. Use only in a trusted local development environment; authentication, TLS and configurable bind addresses have not been added.

## WebSocket smoke check

Stop any server using port 8080 first, then run from the repository root:

```sh
sh scripts/smoke-baseline.sh
```

This compiles a standalone Java probe, launches the freshly built server, connects real WebSocket clients and checks public/private messaging and history after a restart. It uses synthetic messages in a temporary directory; that directory is printed and retained with its server log for inspection. Probe classes are removed automatically. Only the server process created by the probe is stopped.

The historical script name is retained, but its expectations now verify that repeated JOIN is rejected and the original name is released on disconnect. It also checks that private-message text is absent from the server log.

This is a server smoke check, not a browser UI test or load test. The PM isolation check covers a short observed scenario, not every possible concurrent interleaving.

## Next steps

1. Completed: registration, validation, private-message edge cases and previews, with regression tests.
2. Consolidate the protocol and synchronize history and live messages.
3. Improve persistence and bounded resource handling.
4. Improve the interface and reconnection behavior.
5. Add reproducible packaging and CI.
6. Publish actual measurements, screenshots and a short demo.

The coursework report, personal chat history, IDE settings and existing build artifacts are intentionally excluded. No license has been selected yet.

## Current validation rules

- Case-sensitive names: 1–50 Unicode letters/digits, `_` or `-`, after trimming outer spaces.
- Message bodies: at most 25 Unicode code points (including emoji); private-recipient prefixes are excluded. Empty/whitespace-only messages require an attachment. Overlength messages are rejected, not truncated.
- PNG/JPEG attachments: 1 byte to 1 MiB; avatars: 1 byte to 200 KiB.
- Server checks the signature, matching MIME and image header dimensions: at most 4096 pixels per side and 16,000,000 pixels total.
- Image checks read bounded headers in memory; they do not decode all pixels, sanitize/re-encode images or guarantee that every corrupted pixel stream is detected.
- Invalid file selection clears the old selection; in-flight older reads cannot restore it.
