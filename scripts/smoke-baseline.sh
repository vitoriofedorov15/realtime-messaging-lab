#!/usr/bin/env sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
jar="$project_dir/server/target/chat-server-1.0-SNAPSHOT-jar-with-dependencies.jar"
if [ ! -f "$jar" ]; then
    echo 'Build first: mvn -f server/pom.xml clean package' >&2
    exit 1
fi
probe_dir=$(mktemp -d)
trap 'rm -rf "$probe_dir"' EXIT HUP INT TERM
java_bin=java
javac_bin=javac
if [ -n "${JAVA_HOME:-}" ]; then
    java_bin="$JAVA_HOME/bin/java"
    javac_bin="$JAVA_HOME/bin/javac"
fi
"$javac_bin" -cp "$jar" -d "$probe_dir" "$project_dir/scripts/BaselineSmoke.java"
cd "$probe_dir"
"$java_bin" -cp "$probe_dir:$jar" BaselineSmoke "$jar"
