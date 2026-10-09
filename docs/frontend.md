# Common — React frontend

Common gives the existing Netty / Protobuf chat a responsive English interface.

## Product behavior

The app initially opens a clearly labelled **Preview** with synthetic messages and original bundled artwork. Demo messages stay in component memory and are never sent to a server. The sample participants do not automatically reply.

Choose **Join a live room**, enter a name and optionally attach an avatar to connect to the real backend. Public messages, private conversations, images, participant updates and history use protocol v2. Switching to live clears demo content. No registration or identity verification is implied by choosing a name.

- General conversation and individual threads.
- Search within the current conversation.
- Saved messages, held in memory for the current app session.
- Image attachments, previews and a larger image viewer.
- Emoji picker, per-conversation drafts and a visible 25-code-point counter.
- Connection state, ten-second reconnect countdown and unsuccessful-message states.
- Responsive navigation drawer and room details on narrow screens.

Bookmarks and drafts are not persistent accounts or server-side features. The message counter preserves the existing backend's 25-character contract. The illustration is an SVG demo asset; user uploads remain PNG/JPEG only.

## Stack and source

React 19, TypeScript, Vite, Lucide React, CSS, protobuf.js. The Java 17 / Netty backend is unchanged in this frontend phase.

`src/chat.ts` owns the WebSocket connection and protocol handling. `src/App.tsx` renders the interface. The UI imports `protocol/chat.proto` through Vite's raw import, so there is still one schema. `src/protocol-state.js` is shared with the existing Node checks and integration runner.

## Run

```sh
npm ci --prefix client --ignore-scripts
npm run dev --prefix client
```

Open `http://127.0.0.1:5173`. Demo mode works without Java.

For live mode, start the Java server as described in the root README. Vite proxies `/ws` to `127.0.0.1:8080`. The preview server uses the same proxy.

```sh
npm run build --prefix client
npm run preview --prefix client
```

Open `http://127.0.0.1:4173` for the production-build preview. A static deployment needs a reverse proxy for `/ws` or a build-time `VITE_WS_URL`; the Java backend remains a separate running service. No external fonts, photos or CDN runtime scripts are needed.

## Scope change

The user prioritized visual quality and portfolio presentation over further infrastructure work. The previous broad reliability and benchmark phases are deferred. Known backend persistence/backpressure limitations remain documented in `known-issues.md`; this redesign does not claim to solve them.

## Verification in this environment

TypeScript checking and a production Vite build completed. The 7 existing client/protocol checks passed. The Java server code was not changed in this phase.

Desktop browser QA completed against the user-started Vite and Java servers on 2026-11-13 at 1280 × 720. Verified demo sending, session bookmarks, conversation search, the image viewer and Escape dismissal. Two independent browser tabs joined as synthetic users ReviewAlice and ReviewBob; a public message appeared in the other tab and a private reply appeared in the intended thread. Both tabs reported no console errors during this scenario.

The [desktop screenshot](screenshots/common-desktop.jpg) captures the actual running demo. It is not a mockup.

Mobile visual QA remains outstanding: the browser viewport API accepted a 390 × 844 override but the actual page and screenshots remained 1280 × 720, including in a fresh tab. No mobile screenshot or mobile verification is claimed. Live image uploads, reconnect behavior and virtual-keyboard behavior were not rechecked in this pass.

The earlier server/integration results in stage reports apply to their stated scenarios, not to every new React control.
