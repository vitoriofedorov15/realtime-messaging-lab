# Realtime Messaging Lab

A coursework WebSocket chat built with Java, Netty and Protocol Buffers, being developed into a reproducible engineering portfolio project.

**Status: stage 0 — source baseline.** The application code is unchanged from the coursework version. Basic server scenarios have been checked; known defects remain. This is not a production service.

## Current implementation

- Java 17 / Netty server, browser client using plain JavaScript.
- Binary WebSocket messages encoded with Protocol Buffers.
- Named users, public and private messages, image attachments and avatars.
- File-based public message history.

See [baseline verification](docs/baseline.md) for what was actually tested and [known issues](docs/known-issues.md) for limitations.

## Build

Requirements: JDK 17 and Maven (verified with JDK 17.0.19 and Maven 3.9.12). Set `JAVA_HOME` to your JDK 17 installation if another Java version is the default. A first build needs access to Maven Central; subsequent builds can use a populated local Maven cache.

From the repository root:

```sh
mvn -f server/pom.xml clean package
```

This generates Java protocol classes from `server/src/main/proto/chat.proto` and builds the executable JAR. No prebuilt JAR is committed. There are currently no Maven unit tests; a successful build alone does not verify application behavior.

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

## Baseline smoke check

Stop any server using port 8080 first, then run from the repository root:

```sh
sh scripts/smoke-baseline.sh
```

This compiles a standalone Java probe, launches the freshly built server, connects real WebSocket clients and checks public/private messaging and history after a restart. It uses synthetic messages in a temporary directory; that directory is printed and retained with its server log for inspection. Probe classes are removed automatically. Only the server process created by the probe is stopped.

**Important:** the last check intentionally reproduces a known repeated-JOIN defect. A successful baseline run means that the original behavior was reproduced, not that the defect is fixed. Stage 1 will replace that expectation with a regression check for correct behavior.

This is a server smoke check, not a browser UI test or load test. The PM isolation check covers a short observed scenario, not every possible concurrent interleaving.

## Next steps

1. Fix registration, validation, private-message edge cases and previews; add regression tests.
2. Consolidate the protocol and synchronize history and live messages.
3. Improve persistence and bounded resource handling.
4. Improve the interface and reconnection behavior.
5. Add reproducible packaging and CI.
6. Publish actual measurements, screenshots and a short demo.

The coursework report, personal chat history, IDE settings and existing build artifacts are intentionally excluded. No license has been selected yet.
