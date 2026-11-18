"""Shows a QR code that connects the phone to this laptop. The code holds the secret token,
so it is never printed, and the image is deleted as soon as you're done."""
import json, os, subprocess, shutil
import segno
from .config import BRAIN_TOKEN, DATA


def tailscale_ip():
    exe = shutil.which("tailscale") or "C:/Program Files/Tailscale/tailscale.exe"
    try:
        found = subprocess.run([exe, "ip", "-4"], capture_output=True, text=True, timeout=10).stdout.split()
    except (OSError, subprocess.SubprocessError):
        return None
    return found[0] if found else None


def main():
    ip = tailscale_ip()
    if not ip:
        raise SystemExit("Tailscale isn't on. Turn it on, then try again.")
    if not BRAIN_TOKEN:
        raise SystemExit("Chacha isn't set up on this laptop yet.")
    path = DATA / "connect_qr.png"
    segno.make(json.dumps({"url": f"http://{ip}:8765", "token": BRAIN_TOKEN}), error="m").save(path, scale=12, border=4)
    try:
        os.startfile(path)
        input("Scan the code with the Chacha app, then press Enter here.")
    finally:
        path.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
