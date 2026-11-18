"""Understands a photo or screenshot the user shares with Chacha.
Offline: a local vision model (VISION_MODEL, pulled once with `ollama pull`). Claude mode: Claude sees it.
Anything that looks like banking is refused, and the image itself is never stored."""
import base64, os, time
import httpx
from . import db, llm
from .banking import is_banking
from .config import OLLAMA_URL

VISION_MODEL = os.getenv("VISION_MODEL", "qwen2.5vl:3b")
PROMPT = ("The user shared this image with their personal assistant. Today is {today}. {q}\n"
          "Only state what is actually visible; never add a year, price or detail that isn't shown.\n"
          "Answer in plain, short sentences. If it shows a bill, ticket, booking or event, list the key details "
          "(what, when, where, amount). If it shows bank, card or payment details, reply only: BANKING.")


def _local_ready():
    try:
        names = [m["name"] for m in httpx.get(f"{OLLAMA_URL}/api/tags", timeout=5).json().get("models", [])]
    except httpx.HTTPError:
        return False
    return any(n == VISION_MODEL or n.split(":")[0] == VISION_MODEL for n in names)


def _claude(img, prompt):
    import anthropic
    resp = anthropic.Anthropic().messages.create(model=llm.CLAUDE_MODEL, max_tokens=2000, messages=[{"role": "user", "content": [
        {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": img}},
        {"type": "text", "text": prompt}]}])
    return "".join(b.text for b in resp.content if b.type == "text").strip()


def _local(img, prompt):
    r = httpx.post(f"{OLLAMA_URL}/api/chat", timeout=600, json={
        "model": VISION_MODEL, "stream": False,
        "messages": [{"role": "user", "content": prompt, "images": [img]}]})
    r.raise_for_status()
    return r.json()["message"]["content"].strip()


def look(jpeg: bytes, question: str = ""):
    img = base64.b64encode(jpeg).decode()
    prompt = PROMPT.format(today=time.strftime("%A %d %B %Y"), q=question.strip() or "What is this, and is there anything I should do about it?")
    if llm.claude_available():
        db.log_cloud("image: " + prompt)
        answer = _claude(img, prompt)
    elif _local_ready():
        answer = _local(img, prompt)
    else:
        return "I can't look at pictures offline yet. On the laptop, run: ollama pull " + VISION_MODEL
    if answer.strip().upper().startswith("BANKING") or is_banking("", answer):
        return "That looks like banking or payment details, so I left it alone."
    db.run("INSERT INTO items(ts,source,app,person,direction,text,category,processed) VALUES (?,?,?,?,?,?,?,0)",
           (time.time(), "phone", "photo", "You", "in", f"Shared a picture. {question} {answer}".strip(), None))
    return answer
