#!/usr/bin/env sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
npm ci --prefix "$project_dir/client" --ignore-scripts
npm run build --prefix "$project_dir/client"
npm test --prefix "$project_dir/client"
mvn -f "$project_dir/server/pom.xml" clean package "$@"
