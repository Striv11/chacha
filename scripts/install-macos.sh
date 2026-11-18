#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p data
exec > >(tee -a data/install_log.txt) 2>&1

step() { printf '\n\033[1;36m[%s/6] %s\033[0m\n' "$1" "$2"; }
ok() { printf '  \033[0;32m✓ %s\033[0m\n' "$1"; }
die() { printf '\n\033[0;31mSetup stopped: %s\033[0m\n' "$1"; exit 1; }

[[ "$(uname -s)" == "Darwin" ]] || die "This installer is for macOS."

step 1 "Checking this Mac"
free_kb="$(df -Pk "$ROOT" | awk 'NR==2 {print $4}')"
ram_bytes="$(sysctl -n hw.memsize)"
free_gb=$((free_kb / 1024 / 1024))
ram_gb=$((ram_bytes / 1024 / 1024 / 1024))
printf '  Memory: %s GB · Free space: %s GB\n' "$ram_gb" "$free_gb"
(( free_gb >= 10 )) || die "Chacha needs at least 10 GB free for its local models."
command -v brew >/dev/null || die "Install Homebrew from https://brew.sh, then run this again."
ok "This Mac has enough room."

step 2 "Installing local prerequisites"
brew list python@3.12 >/dev/null 2>&1 || brew install python@3.12
brew list ollama >/dev/null 2>&1 || brew install ollama
brew services start ollama >/dev/null
export PATH="/opt/homebrew/bin:/opt/homebrew/opt/python@3.12/bin:$PATH"
PYTHON="/opt/homebrew/opt/python@3.12/bin/python3.12"
[[ -x "$PYTHON" ]] || PYTHON="$(command -v python3)"
ok "Python and Ollama are ready."

step 3 "Installing Chacha"
[[ -x .venv/bin/python ]] || "$PYTHON" -m venv .venv
.venv/bin/python -m pip install -q --disable-pip-version-check -r requirements.txt
.venv/bin/python -m playwright install chromium >/dev/null
ok "Backend dependencies and private browser installed."

step 4 "Creating private settings"
[[ -f .env ]] || cp .env.example .env
.venv/bin/python - <<'PY'
import secrets
from pathlib import Path

path = Path('.env')
lines = path.read_text(encoding='utf8-sig').splitlines()
values = {line.split('=', 1)[0]: line.split('=', 1)[1] for line in lines if '=' in line and not line.startswith('#')}
updates = {}
if not values.get('BRAIN_TOKEN', '').strip():
    updates['BRAIN_TOKEN'] = secrets.token_urlsafe(32)
updates['FAST_MODEL'] = values.get('FAST_MODEL') or 'qwen3:4b'
updates['SMART_MODEL'] = 'qwen3:4b'
updates['VISION_MODEL'] = values.get('VISION_MODEL') or 'qwen2.5vl:3b'
updates['CONTEXT_TOKENS'] = '8192'
seen = set()
out = []
for line in lines:
    key = line.split('=', 1)[0] if '=' in line else ''
    if key in updates:
        out.append(f'{key}={updates[key]}')
        seen.add(key)
    else:
        out.append(line)
for key, value in updates.items():
    if key not in seen:
        out.append(f'{key}={value}')
path.write_text('\n'.join(out) + '\n', encoding='utf8')
path.chmod(0o600)
PY
ok "A private browser token was created."

step 5 "Downloading local AI models"
for model in qwen3:4b qwen2.5vl:3b; do
  if ! ollama list | awk 'NR>1 {print $1}' | grep -qx "$model"; then
    ollama pull "$model"
  fi
done
ok "Text and vision models are available offline."

step 6 "Starting Chacha"
.venv/bin/python -m brain.autostart on
for _ in {1..30}; do
  ip="$(tailscale ip -4 2>/dev/null | head -1 || true)"
  host="${ip:-127.0.0.1}"
  if curl -fsS --max-time 2 "http://${host}:8765/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
.venv/bin/python scripts/doctor.py
url="http://${host}:8765"
open "$url"
ok "Chacha is live at $url and will start when you sign in."
printf '\nPaste BRAIN_TOKEN from %s/.env into the dashboard once.\n' "$ROOT"
