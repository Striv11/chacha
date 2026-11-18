"""Tools Chacha and its agents can use. Nothing here sends messages or pays for anything:
anything that acts on the phone is only proposed, and runs when the user taps it there."""
import datetime, ipaddress, json, socket, time, httpx
from urllib.parse import urlsplit
from bs4 import BeautifulSoup
from . import db, maps, gmail, browser
from .banking import is_banking


def web_search(query: str, n: int = 6):
    """Search engines fail or rate-limit unpredictably, so try several in turn."""
    from ddgs import DDGS
    last = "no results"
    for backend in ("bing", "google", "yahoo", "duckduckgo", "brave", "mojeek"):
        try:
            res = DDGS().text(query, max_results=n, backend=backend)
            if res:
                return [{"title": r.get("title"), "url": r.get("href"), "snippet": r.get("body")} for r in res]
        except Exception as e:
            last = str(e)
    return {"error": f"search failed on every engine: {last}"}


def _public(url):
    """Blocks the laptop, the home network and Tailscale devices, including via redirects."""
    u = urlsplit(url)
    if u.scheme not in ("http", "https") or not u.hostname:
        return False
    try:
        addrs = socket.getaddrinfo(u.hostname, u.port or 443)
    except OSError:
        return False
    return all(ipaddress.ip_address(a[4][0].split("%")[0]).is_global for a in addrs)


def fetch_page(url: str, max_chars: int = 5000):
    try:
        for _ in range(5):
            if not _public(url):
                return {"error": "only public websites can be opened"}
            r = httpx.get(url, timeout=20, follow_redirects=False,
                          headers={"User-Agent": "Mozilla/5.0 (personal assistant)"})
            if not r.is_redirect:
                break
            url = str(r.url.join(r.headers["location"]))
        else:
            return {"error": "too many redirects"}
        soup = BeautifulSoup(r.text, "html.parser")
        for t in soup(["script", "style", "nav", "footer", "header", "noscript"]):
            t.decompose()
        text = " ".join(soup.get_text(" ").split())
        return {"url": str(r.url), "text": text[:max_chars]}
    except Exception as e:
        return {"error": f"fetch failed: {e}"}


def search_memory(query: str, n: int = 10):
    q = db.fts_query(query)
    items = db.rows(
        "SELECT i.id, datetime(i.ts,'unixepoch','localtime') AS time, i.app, i.person, i.direction, "
        "substr(i.text,1,300) AS text FROM items_fts f JOIN items i ON i.id=f.rowid "
        "WHERE items_fts MATCH ? ORDER BY rank LIMIT ?", (q, n))
    mems = db.rows("SELECT fact FROM memories WHERE " +
                   " OR ".join(["fact LIKE ?"] * max(1, len(query.split()))) + " LIMIT 10",
                   tuple(f"%{w}%" for w in (query.split() or [""])))
    return {"messages": items, "facts": [m["fact"] for m in mems]}


def create_task(title: str, due: str = "", kind: str = "todo"):
    tid = db.run("INSERT INTO tasks(ts,title,due,kind) VALUES (?,?,?,?)", (time.time(), title, due, kind))
    return {"task_id": tid}


def remember(fact: str):
    db.run("INSERT INTO memories(ts,fact) VALUES (?,?)", (time.time(), fact))
    return {"ok": True}


def notify_user(title: str, body: str):
    db.notify(title, body, "agent")
    return {"ok": True}


def _synced(key):
    return bool(db.get(key))


def read_calendar(days: int = 7):
    if not _synced("synced_calendar"):
        return {"error": "The calendar has not synced from the phone yet. Tell the user."}
    now = datetime.datetime.now()
    try:
        days = max(int(days), 2)  # a small model asking for "1 day" must still see tomorrow
    except (TypeError, ValueError):
        days = 7
    end = now + datetime.timedelta(days=days)
    rows = db.rows("SELECT title,starts,ends,location FROM calendar WHERE ends>=? AND starts<=? ORDER BY starts LIMIT 40",
                   (now.strftime("%Y-%m-%d %H:%M"), end.strftime("%Y-%m-%d %H:%M")))
    out = {"now": f"{now:%a %d %b %H:%M}", "today": [], "tomorrow": [], "later": []}
    for r in rows:
        begin = datetime.datetime.fromisoformat(r["starts"])
        gap = (begin.date() - now.date()).days
        title = " ".join((r["title"] or "").split())
        title = title if len(title) <= 80 else title[:80].rsplit(" ", 1)[0] + "…"
        where = " ".join((r["location"] or "").split())
        where = where if len(where) <= 70 else where[:70].rsplit(" ", 1)[0] + "…"
        place = f" at {where}" if where else ""
        line = f"{r['starts'][11:]}-{r['ends'][11:]} {title}{place}"
        if gap <= 0:
            out["today"].append(line)
        elif gap == 1:
            out["tomorrow"].append(line)
        else:
            out["later"].append(f"{begin:%a %d %b} {line}")
    return out


def find_contact(name: str):
    if not _synced("synced_contacts"):
        return {"error": "Contacts have not synced from the phone yet. Tell the user."}
    return {"contacts": db.rows("SELECT name,number FROM contacts WHERE name LIKE ? LIMIT 8", (f"%{name}%",))}


def recent_calls(n: int = 15):
    if not _synced("synced_calls"):
        return {"error": "The call log has not synced from the phone yet. Tell the user."}
    return {"calls": db.rows("SELECT datetime(ts,'unixepoch','localtime') AS time,name,number,kind FROM calls "
                             "ORDER BY ts DESC LIMIT ?", (int(n),))}


def where_am_i():
    loc = db.get("location")
    return json.loads(loc) if loc else {"error": "Location has not synced from the phone yet. Tell the user."}


def propose(kind, summary, payload):
    """Queues an action for the user's tap. Identical pending proposals are not repeated."""
    if not db.rows("SELECT 1 FROM actions WHERE status='proposed' AND kind=? AND summary=? AND payload=?",
                   (kind, summary, db.dumps(payload))):
        db.run("INSERT INTO actions(ts,kind,summary,payload) VALUES (?,?,?,?)",
               (time.time(), kind, summary, db.dumps(payload)))
    return {"status": "Ready. NOT done yet: the user must tap to approve it in the app. Say exactly that."}


def _number(person):
    r = db.rows("SELECT number FROM contacts WHERE name LIKE ? LIMIT 1", (f"%{person}%",))
    return r[0]["number"] if r else ""


def draft_reply(person: str, text: str):
    return propose("reply", f"Reply to {person}", {"person": person, "text": text, "number": _number(person)})


def add_event(title: str, start: str, end: str = "", location: str = ""):
    try:
        begin = datetime.datetime.fromisoformat(start)
        finish = datetime.datetime.fromisoformat(end) if end else begin + datetime.timedelta(hours=1)
    except ValueError:
        return {"error": "use 'YYYY-MM-DD HH:MM' for start and end"}
    return propose("event", f"Add to calendar: {title}, {begin:%a %d %b %H:%M}",
                   {"title": title, "start": f"{begin:%Y-%m-%d %H:%M}", "end": f"{finish:%Y-%m-%d %H:%M}", "location": location})


def call_person(person: str):
    number = _number(person)
    if not number:
        return {"error": f"No number for {person} in the synced contacts."}
    return propose("call", f"Call {person}", {"person": person, "number": number})


def navigate(destination: str):
    return propose("navigate", f"Directions to {destination}", {"destination": destination})


def set_alarm(time: str, label: str = ""):
    try:
        t = datetime.datetime.strptime(time, "%H:%M")
    except ValueError:
        return {"error": "use 'HH:MM' in 24-hour time"}
    return propose("alarm", f"Alarm at {t:%H:%M} {label}".strip(), {"hour": t.hour, "minute": t.minute, "label": label})


def start_background(goal: str):
    from . import agents
    return {"agent_id": agents.create(goal, origin="user"), "status": "Working on it in the background; the user gets a notification when it's done."}


def watch(goal: str, every_hours: float = 24):
    if db.rows("SELECT count(*) n FROM watches")[0]["n"] >= 10:
        return {"error": "Already keeping an eye on 10 things. Ask the user which to stop."}
    every = max(1.0, float(every_hours))
    return {"watch_id": db.run("INSERT INTO watches(goal,every_hours,next_run) VALUES (?,?,?)", (goal, every, time.time())), "status": f"Will check every {every:g} hours."}


def list_watches():
    return {"watches": db.rows("SELECT id,goal,every_hours FROM watches")}


def stop_watch(id: int):
    db.run("DELETE FROM watches WHERE id=?", (int(id),))
    return {"ok": True}


def draft_email(to: str, subject: str, body: str):
    if not gmail.connected():
        return gmail.NOT_CONNECTED
    return propose("email", f"Email {to}: {subject}", {"to": to, "subject": subject, "text": body})


def remind_at_place(text: str, place: str):
    """place can be a name or address, or 'here' for where the phone is now."""
    if str(place).strip().lower() in ("here", "this place", "current location"):
        here = maps._here()
        if not here:
            return {"error": "Location has not synced from the phone yet. Tell the user."}
        lat, lon, name = here["lat"], here["lon"], "the spot where you set this"
    else:
        try:
            found = maps.locate(place)
        except Exception as e:
            return {"error": f"Maps failed: {e}"}
        if not found:
            return {"error": f"Couldn't find {place} on the map. Ask the user for a more exact name or address."}
        lat, lon, name = found
    if db.rows("SELECT count(*) n FROM place_reminders")[0]["n"] >= 20:
        return {"error": "There are already 20 place reminders. Ask the user which to drop."}
    rid = db.run("INSERT INTO place_reminders(text,place,lat,lon) VALUES (?,?,?,?)", (text, name, lat, lon))
    return {"id": rid, "status": f"Will remind the user when they reach {name}. Checked every few minutes, so it may be a little late."}


def list_place_reminders():
    return {"reminders": db.rows("SELECT id,text,place FROM place_reminders")}


def drop_place_reminder(id: int):
    db.run("DELETE FROM place_reminders WHERE id=?", (int(id),))
    return {"ok": True}


def phone_task(app: str, goal: str):
    """Any app the user has allowed in Chacha's settings; the phone checks the list, banking apps never qualify."""
    app = str(app).lower().strip()
    if not app or is_banking(app, app):
        return {"error": "Chacha never uses banking or payment apps."}
    return propose("phone_task", f"In {app}: {goal}", {"app": app, "goal": goal})


def open_link(url: str):
    if urlsplit(url).scheme not in ("http", "https"):
        return {"error": "Only web links can be opened."}
    return propose("open", f"Open {urlsplit(url).hostname}", {"url": url})


WORLD_TOOLS = {  # may use Claude; can never see personal data
    "web_search": (web_search, "web_search(query) -> list of results"),
    "fetch_page": (fetch_page, "fetch_page(url) -> page text"),
    "browser_open": (browser.browser_open, "browser_open(url) -> opens a site in a real browser; numbered elements"),
    "browser_click": (browser.browser_click, "browser_click(n) -> clicks element n"),
    "browser_type": (browser.browser_type, "browser_type(n, text) -> types into element n"),
}

PRIVATE_TOOLS = {  # always local; can never reach the internet directly
    "search_memory": (search_memory, "search_memory(query) -> user's past messages and known facts"),
    "create_task": (create_task, "create_task(title, due='YYYY-MM-DD HH:MM', kind='todo|reminder|promise')"),
    "remember": (remember, "remember(fact) -> store a durable fact about the user"),
    "notify_user": (notify_user, "notify_user(title, body) -> push a notification to the user's phone"),
    "read_calendar": (read_calendar, "read_calendar(days=7) -> the user's upcoming calendar events"),
    "find_contact": (find_contact, "find_contact(name) -> phone contacts matching a name"),
    "recent_calls": (recent_calls, "recent_calls(n=15) -> recent incoming, outgoing and missed calls"),
    "where_am_i": (where_am_i, "where_am_i() -> the phone's last known coordinates"),
    "route_to": (maps.route_to, "route_to(destination) -> driving time and distance from the phone (no live traffic)"),
    "find_place": (maps.find_place, "find_place(query) -> places near the phone, e.g. 'pharmacy Indiranagar'"),
    "search_mail": (gmail.search_mail, "search_mail(query='in:inbox', n=8) -> emails, using Gmail search words like from: or newer_than:2d"),
    "read_mail": (gmail.read_mail, "read_mail(id) -> the full text of one email"),
    "draft_email": (draft_email, "draft_email(to, subject, body) -> prepares an email; the user taps to send it"),
    "phone_task": (phone_task, "phone_task(app, goal) -> after the user's tap, operates an app by its name (swiggy, uber, amazon, whatsapp, maps, or any app the user allowed) and stops before anything final"),
    "remind_at_place": (remind_at_place, "remind_at_place(text, place) -> reminds the user when they arrive somewhere; place is a name, address or 'here'"),
    "list_place_reminders": (list_place_reminders, "list_place_reminders() -> reminders waiting for a place"),
    "drop_place_reminder": (drop_place_reminder, "drop_place_reminder(id) -> cancels a place reminder"),
    "open_link": (open_link, "open_link(url) -> the user taps to open a link on their phone"),
    "start_background": (start_background, "start_background(goal) -> keeps working after this chat ends and notifies the user when done"),
    "watch": (watch, "watch(goal, every_hours=24) -> a job repeated on its own, e.g. 'tell me when X drops below Y'"),
    "list_watches": (list_watches, "list_watches() -> the jobs currently being repeated"),
    "stop_watch": (stop_watch, "stop_watch(id) -> stops a repeating job"),
    "draft_reply": (draft_reply, "draft_reply(person, text) -> prepares a message; the user taps to send it"),
    "add_event": (add_event, "add_event(title, start='YYYY-MM-DD HH:MM', end='', location='') -> the user taps to add it"),
    "call_person": (call_person, "call_person(person) -> the user taps to open the dialler with their number"),
    "navigate": (navigate, "navigate(destination) -> the user taps to start Google Maps directions"),
    "set_alarm": (set_alarm, "set_alarm(time='HH:MM', label='') -> the user taps to set a phone alarm"),
}
