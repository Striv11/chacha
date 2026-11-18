"""The wall between personal data and the outside world."""
import re
from . import db, llm

PROMPT = """Rewrite this task so it can be given to an outside service.
Remove every person's name, phone number, email, home/work address, company the user works at, account or booking number, and anything quoted from private messages.
Keep what is needed to do the task: places, dates, budgets, products, public events.
Example: "Find a Goa flight for me and Rahul on Friday, Rahul said under 8000" -> "Find flights to Goa for 2 people on Friday, under 8000 per person"
Task: {goal}
Return JSON: {{"task": "..."}}"""


def scrub(text):
    """Deterministic pass: always runs, even if the model misses something."""
    text = re.sub(r"[\w.+-]+@[\w-]+\.[\w.-]+", "[email]", text)
    text = re.sub(r"\+?\d[\d\s-]{7,}\d", "[number]", text)
    for p in db.rows("SELECT name FROM people WHERE length(name)>2"):
        text = re.sub(rf"\b{re.escape(p['name'])}\b", "someone", text, flags=re.I)
    return text


def redact(goal):
    out = llm.chat([{"role": "user", "content": PROMPT.format(goal=goal)}], as_json=True)
    task = str(out.get("task") or "").strip()
    if not task:
        raise RuntimeError("Could not safely rewrite the task, so nothing was sent out.")
    return scrub(task)
