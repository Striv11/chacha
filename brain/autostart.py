"""Start Chacha automatically after sign-in on Windows or macOS.
  python -m brain.autostart on
  python -m brain.autostart off"""
import os, plistlib, subprocess, sys
from pathlib import Path
from .config import ROOT

WINDOWS_LINK = (Path(os.environ["APPDATA"]) / "Microsoft/Windows/Start Menu/Programs/Startup/Chacha.vbs"
                if os.name == "nt" else None)
MAC_LINK = Path.home() / "Library/LaunchAgents/com.chacha.brain.plist"


def on():
    if sys.platform == "darwin":
        MAC_LINK.parent.mkdir(parents=True, exist_ok=True)
        config = {
            "Label": "com.chacha.brain",
            "ProgramArguments": [str(ROOT / ".venv/bin/python"), "-m", "brain.run"],
            "WorkingDirectory": str(ROOT),
            "EnvironmentVariables": {"PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"},
            "RunAtLoad": True,
            "StandardOutPath": str(ROOT / "data/chacha-launch.log"),
            "StandardErrorPath": str(ROOT / "data/chacha-launch.log"),
        }
        MAC_LINK.write_bytes(plistlib.dumps(config))
        domain = f"gui/{os.getuid()}"
        subprocess.run(["launchctl", "bootout", domain, str(MAC_LINK)], capture_output=True)
        subprocess.run(["launchctl", "bootstrap", domain, str(MAC_LINK)], check=True)
        print("Chacha will start by itself when you sign in.")
        return
    if os.name != "nt":
        raise SystemExit("Automatic startup is supported on Windows and macOS.")
    pythonw = ROOT / ".venv/Scripts/pythonw.exe"
    WINDOWS_LINK.write_text(
        'Set s = CreateObject("WScript.Shell")\r\n'
        f's.CurrentDirectory = "{ROOT}"\r\n'
        f's.Run """{pythonw}"" -m brain.run", 0, False\r\n', encoding="utf8")
    print("Chacha will start by itself when you sign in.")


def off():
    if sys.platform == "darwin":
        subprocess.run(["launchctl", "bootout", f"gui/{os.getuid()}", str(MAC_LINK)], capture_output=True)
        MAC_LINK.unlink(missing_ok=True)
    elif WINDOWS_LINK:
        WINDOWS_LINK.unlink(missing_ok=True)
    print("Chacha won't start by itself any more.")


if __name__ == "__main__":
    {"on": on, "off": off}.get((sys.argv[1:] or ["on"])[0], on)()
