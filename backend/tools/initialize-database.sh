#!/usr/bin/env bash
set -euo pipefail
backend_dir="$(cd "$(dirname "$0")/.." && pwd)"
dev_config="$backend_dir/src/main/resources/application-dev.yml"
if [[ ! -f "$dev_config" ]]; then
  printf '%s\n' 'Missing backend/src/main/resources/application-dev.yml. Copy application-dev.yml.example and fill in local values.' >&2
  exit 1
fi
mvn -B -ntp -f "$backend_dir/pom.xml" dependency:build-classpath -DincludeScope=runtime \
  "-Dmdep.outputFile=$backend_dir/target/database-runtime-classpath.txt"
runtime_classpath="$(cat "$backend_dir/target/database-runtime-classpath.txt")"
exec java -cp "$runtime_classpath" "$backend_dir/tools/InitializeDatabase.java" \
  "$backend_dir/src/main/resources/db/migration" "$dev_config"
