#!/usr/bin/env bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
./scripts/install-macos.sh
printf '\nPress Return to close this window.'
read -r
