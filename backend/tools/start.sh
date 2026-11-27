#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/local-env.sh"
exec mvn -f "$backend_dir/pom.xml" spring-boot:run "$@"
