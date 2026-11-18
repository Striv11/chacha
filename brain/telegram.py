"""Talk to Chacha from Telegram. Needs TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID in .env.
Only that one chat is answered; anyone else gets nothing but their own ID, so you can find yours."""
import os, time, traceback
import httpx
from . import think

API = "https://api.telegram.org/bot{token}/{method}"


def _call(method, **params):
    r = httpx.post(API.format(token=os.environ["TELEGRAM_BOT_TOKEN"], method=method), json=params, timeout=70)
    r.raise_for_status()
    return r.json().get("result")


def send(chat, text):
    _call("sendMessage", chat_id=chat, text=str(text)[:4000])


def handle(update):
    msg = update.get("message") or {}
    chat, text = str((msg.get("chat") or {}).get("id", "")), msg.get("text") or ""
    if not chat or not text:
        return
    if chat != os.getenv("TELEGRAM_CHAT_ID", ""):
        send(chat, f"This Chacha is private. Your chat ID is {chat}.")
        return
    out = think.ask(text)
    send(chat, out["question"] or out["result"] or "I couldn't finish that one. Could you try again?")


def loop():
    offset = 0
    while True:
        try:
            for u in _call("getUpdates", offset=offset, timeout=50) or []:
                offset = u["update_id"] + 1
                handle(u)
        except Exception as e:  # never log the URL: it contains the bot token
            print(f"Telegram: {type(e).__name__}", flush=True)
            time.sleep(15)


def enabled():
    return bool(os.getenv("TELEGRAM_BOT_TOKEN"))
