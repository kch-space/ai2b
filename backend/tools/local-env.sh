#!/usr/bin/env bash
# Sourced only by project scripts. Credentials remain in the current process.
backend_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$backend_dir/.env.local" ]]; then
  set -a
  source "$backend_dir/.env.local"
  set +a
fi
: "${DB_USERNAME:?Set DB_USERNAME in backend/.env.local or your environment}"
: "${DB_PASSWORD:?Set DB_PASSWORD in backend/.env.local or your environment}"
