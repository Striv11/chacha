"""Talk to your brain from the laptop terminal: python chat.py"""
import sys; sys.stdout.reconfigure(encoding="utf-8")
from brain import db, think
db.init()
print("Chacha. Type a message ('brief' for a brief, 'quit' to exit).")
while True:
    q = input("\nyou> ").strip()
    if q in ("quit", "exit"): break
    if q == "brief": print(think.brief()); continue
    r = think.ask(q)
    print("\nbrain>", r.get("question") or r.get("result"))
