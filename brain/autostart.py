"""Start Chacha by itself when you sign in to Windows.
  python -m brain.autostart on    adds a hidden starter to your Startup folder
  python -m brain.autostart off   removes it"""
import os, sys
from pathlib import Path
from .config import ROOT

LINK = Path(os.environ["APPDATA"]) / "Microsoft/Windows/Start Menu/Programs/Startup/Chacha.vbs"


def on():
    pythonw = ROOT / ".venv/Scripts/pythonw.exe"
    LINK.write_text(
        'Set s = CreateObject("WScript.Shell")\r\n'
        f's.CurrentDirectory = "{ROOT}"\r\n'
        f's.Run """{pythonw}"" -m brain.run", 0, False\r\n', encoding="utf8")
    print("Chacha will start by itself when you sign in.")


def off():
    LINK.unlink(missing_ok=True)
    print("Chacha won't start by itself any more.")


if __name__ == "__main__":
    {"on": on, "off": off}.get((sys.argv[1:] or ["on"])[0], on)()
