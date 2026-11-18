"""Starts everything Chacha needs, in the right order: the local model, then waits for Tailscale, then the server.
Used by auto-start and the installer. Logs go to data/chacha.log."""
import shutil, subprocess, sys, time
import httpx
from .config import DATA, OLLAMA_URL


def ollama_up():
    try:
        return httpx.get(OLLAMA_URL + "/api/tags", timeout=3).status_code == 200
    except httpx.HTTPError:
        return False


def main():
    log = open(DATA / "chacha.log", "a", encoding="utf8", buffering=1)
    sys.stdout = sys.stderr = log
    print(time.strftime("%Y-%m-%d %H:%M"), "starting Chacha")
    if not ollama_up():
        exe = shutil.which("ollama")
        if exe:
            subprocess.Popen([exe, "serve"], stdout=log, stderr=log, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        for _ in range(60):
            if ollama_up():
                break
            time.sleep(2)
    from .connect import tailscale_ip
    for _ in range(60):  # at boot, Tailscale can take a minute; without it the phone can't reach Chacha
        if tailscale_ip():
            break
        time.sleep(3)
    sys.argv = ["brain.server"]
    import runpy
    runpy.run_module("brain.server", run_name="__main__")


if __name__ == "__main__":
    main()
