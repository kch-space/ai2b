#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/local-env.sh"
mvn -B -ntp -f "$backend_dir/pom.xml" dependency:build-classpath -DincludeScope=runtime \
  "-Dmdep.outputFile=$backend_dir/target/database-runtime-classpath.txt"
runtime_classpath="$(cat "$backend_dir/target/database-runtime-classpath.txt")"
exec java -cp "$runtime_classpath" "$backend_dir/tools/InitializeDatabase.java" \
  "$backend_dir/src/main/resources/db/migration"
