"""One chat() for everything. The app's Offline/Claude switch decides where thinking happens:
  offline -> only the local model; nothing goes to Claude, even web tasks
  claude  -> everything goes to Claude, and falls back to the local model if Claude fails
"""
import json, os, re, httpx
from .config import OLLAMA_URL, FAST_MODEL, SMART_MODEL

CLAUDE_MODEL = os.getenv("CLAUDE_MODEL", "claude-opus-5-5")
CLAUDE_FAST_MODEL = os.getenv("CLAUDE_FAST_MODEL", CLAUDE_MODEL)
CLAUDE_EFFORT = os.getenv("CLAUDE_EFFORT", "medium")
CONTEXT = int(os.getenv("CONTEXT_TOKENS", "16384"))  # how much the local AI reads at once; 8192 on 8 GB laptops

_client = None


def _claude(messages, model, as_json):
    global _client
    import anthropic
    if _client is None:
        _client = anthropic.Anthropic()  # reads ANTHROPIC_API_KEY from env / .env
    system = "\n\n".join(m["content"] for m in messages if m["role"] == "system")
    if as_json:
        system += "\n\nRespond with a single valid JSON object only, no prose, no code fences."
    convo = [m for m in messages if m["role"] != "system"]
    if not convo or convo[0]["role"] != "user":
        convo.insert(0, {"role": "user", "content": "Begin."})
    resp = _client.beta.messages.create(
        model=model,
        max_tokens=16000,
        system=system or anthropic.NOT_GIVEN,
        messages=convo,
        output_config={"effort": CLAUDE_EFFORT},
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
    )
    if resp.stop_reason == "refusal":
        raise RuntimeError("Claude declined this request")
    return "".join(b.text for b in resp.content if b.type == "text").strip()


def _ollama(messages, model, as_json, temperature):
    body = {"model": model, "messages": messages, "stream": False, "think": False,
            "options": {"temperature": temperature, "num_ctx": CONTEXT}}
    if as_json:
        body["format"] = "json"
    for attempt in range(3):  # Ollama occasionally returns a 500 mid-answer
        r = httpx.post(f"{OLLAMA_URL}/api/chat", json=body, timeout=600)
        if r.status_code < 500:
            break
        print(f"Ollama {r.status_code} (attempt {attempt + 1}): {r.text[:300]}", flush=True)
    r.raise_for_status()
    return re.sub(r"<think>.*?</think>", "", r.json()["message"]["content"], flags=re.S).strip()


def has_key():
    """Re-reads .env, so a key pasted in later works without restarting Chacha."""
    global _client
    from dotenv import dotenv_values
    from .config import ROOT
    key = (dotenv_values(ROOT / ".env").get("ANTHROPIC_API_KEY") or "").strip()
    if key and key != os.getenv("ANTHROPIC_API_KEY"):
        os.environ["ANTHROPIC_API_KEY"] = key
        _client = None
    return bool(os.getenv("ANTHROPIC_API_KEY"))


def claude_available():
    """True only when the user has switched to Claude and a key exists."""
    from . import db
    return has_key() and db.get("brain_mode", "offline") == "claude"


def _parse(text, as_json):
    if not as_json:
        return text
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        m = re.search(r"\{.*\}", text, re.S)
        return json.loads(m.group(0)) if m else {}


def _send_claude(messages, smart, as_json):
    from . import db
    db.log_cloud(messages[0]["content"] + " ||| " + messages[-1]["content"])
    return _parse(_claude(messages, CLAUDE_MODEL if smart else CLAUDE_FAST_MODEL, as_json), as_json)


def chat(messages, smart=False, as_json=False, temperature=0.3):
    if claude_available():
        try:
            return _send_claude(messages, smart, as_json)
        except Exception as e:  # keep working on the local model rather than go silent
            print(f"Claude failed ({type(e).__name__}); using the local model for this step", flush=True)
    model = SMART_MODEL if smart else FAST_MODEL
    if as_json:
        return _parse(_ollama(messages, model, True, temperature), True)
    # plain mode leaks the model's reasoning into the answer, so always ask for JSON
    wrapped = messages[:-1] + [{"role": messages[-1]["role"], "content": messages[-1]["content"] +
                                chr(10) * 2 + 'Return JSON only: {"text": "<your answer>"}'}]
    parsed = _parse(_ollama(wrapped, model, True, temperature), True)
    out = str(parsed.get("text") or "").strip() if isinstance(parsed, dict) else ""
    if not out:
        raise ValueError("local model returned no text")
    return out
