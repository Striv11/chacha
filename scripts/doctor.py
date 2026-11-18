"""A read-only health check for Chacha's local development/runtime setup."""
import os, shutil, subprocess, sys
from pathlib import Path

import httpx
from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parent.parent
ENV = dotenv_values(ROOT / ".env")
checks = []


def check(name, ok, detail, required=True):
    checks.append((name, bool(ok), detail, required))
    print(f"{'PASS' if ok else 'FAIL' if required else 'WARN'}  {name}: {detail}")


check("Python", sys.version_info >= (3, 12), sys.version.split()[0])
check("Private token", bool((ENV.get("BRAIN_TOKEN") or "").strip()), ".env configured")
check("Database", (ROOT / "data/brain.db").exists(), "data/brain.db")
check("Web dashboard", (ROOT / "web/dist/index.html").exists(), "web/dist/index.html")

try:
    models = {item["name"] for item in httpx.get("http://127.0.0.1:11434/api/tags", timeout=5).json().get("models", [])}
    check("Ollama", True, f"{len(models)} model(s) installed")
    for key in ("SMART_MODEL", "VISION_MODEL"):
        wanted = ENV.get(key, "")
        found = any(name == wanted or name.split(":")[0] == wanted for name in models)
        check(key, found, wanted or "not configured")
except Exception as exc:
    check("Ollama", False, type(exc).__name__)

ip = ""
if shutil.which("tailscale"):
    ip = subprocess.run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=5).stdout.split()
    ip = ip[0] if ip else ""
check("Tailscale", bool(ip), ip or "not connected; local dashboard still works", required=False)

host = ip or "127.0.0.1"
try:
    response = httpx.get(f"http://{host}:8765/health", timeout=5)
    check("Chacha service", response.status_code == 200, f"http://{host}:8765")
except Exception as exc:
    check("Chacha service", False, type(exc).__name__)

failed = [name for name, ok, _, required in checks if required and not ok]
if failed:
    print("\nNeeds attention: " + ", ".join(failed))
    raise SystemExit(1)
print("\nChacha is ready for development.")
