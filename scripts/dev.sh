#!/usr/bin/env sh
# Run from your own terminal. Starts only this project's processes.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
java_bin=java
if [ -n "${JAVA_HOME:-}" ]; then java_bin="$JAVA_HOME/bin/java"; fi
jar="$project_dir/server/target/chat-server-1.0-SNAPSHOT-jar-with-dependencies.jar"
if [ ! -f "$jar" ]; then
    mvn -f "$project_dir/server/pom.xml" package
fi
if [ ! -d "$project_dir/client/node_modules" ]; then
    npm ci --prefix "$project_dir/client" --ignore-scripts
fi
# Refuse occupied ports; do not attach to or stop someone else's server.
node - <<'JS'
const net = require('node:net');
(async () => {
  for (const port of [8080, 5173]) {
    await new Promise((resolve, reject) => {
      const server = net.createServer();
      server.once('error', () => reject(new Error(`Port ${port} is already in use or unavailable. Stop the previous development server first.`)));
      server.listen(port, () => server.close(resolve));
    });
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
JS
mkdir -p "$project_dir/runtime"
backend_pid=''
frontend_pid=''
cleanup() {
    trap - EXIT INT TERM
    if [ -n "$frontend_pid" ]; then kill "$frontend_pid" 2>/dev/null || true; fi
    if [ -n "$backend_pid" ]; then kill "$backend_pid" 2>/dev/null || true; fi
    wait 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
(cd "$project_dir/runtime" && exec "$java_bin" -jar "$jar") &
backend_pid=$!
printf '\nCommon: http://127.0.0.1:5173\nKeep this terminal open. Press Ctrl+C to stop both servers.\n\n'
(cd "$project_dir/client" && exec node node_modules/vite/bin/vite.js --host 127.0.0.1) &
frontend_pid=$!
wait "$frontend_pid"
