"""The thinking loop: what makes the brain 'alive'.
Every few minutes: understand new items -> update people/tasks/memories -> check due
reminders -> morning/evening brief -> notice things worth acting on -> propose agents."""
import re, time, datetime, json, traceback
import httpx
from . import db, llm, agents, tools, maps, gmail, google_calendar

TAG_PROMPT = """You process one message captured from the user's phone. Now: {now}.
Learned user rules: {rules}
{who}
\"\"\"{text}\"\"\"

Pick exactly ONE category: needs_reply, task, promise, event, fyi, money, spam, otp.
importance: integer 0 (ignore) to 5 (urgent, personal, deadline). Friends/family asking something = 4+. Promotions/ads = spam, importance 0, no tasks.
tasks: only real things the USER must do or remember; due = "YYYY-MM-DD HH:MM" only if a time is stated or clearly implied, else "".
Example output:
{{"category": "needs_reply", "importance": 4, "summary": "Priya asks if dinner Saturday 8pm works",
 "tasks": [{{"title": "Reply to Priya about Saturday dinner", "due": "", "kind": "todo"}}],
 "facts": ["Priya is a close friend"], "relation": "friend"}}
Return only JSON like the example."""

CATS = {"needs_reply", "task", "promise", "event", "fyi", "money", "spam", "otp"}


def rules():
    return "; ".join(r["rule"] for r in db.rows("SELECT rule FROM feedback ORDER BY id DESC LIMIT 20")) or "none"


def _process_one(it, now, r):
    if not (it["text"] or "").strip():
        db.run("UPDATE items SET processed=1 WHERE id=?", (it["id"],))
        return
    out = llm.chat([{"role": "user", "content": TAG_PROMPT.format(
        now=now, rules=r, text=it["text"][:2000],
        who=(f"The USER sent this to {it['person']} via {it['app']}:" if it["direction"] == "out"
             else f"{it['person'] or 'Someone'} sent this to the user via {it['app']}:"))}],
        as_json=True)
    out = out if isinstance(out, dict) else {}
    cat = str(out.get("category", "")).strip().lower()
    out["category"] = cat if cat in CATS else "fyi"
    try:
        imp = max(0, min(5, int(out.get("importance") or 0)))
    except (TypeError, ValueError):
        imp = 2
    if out["category"] in ("spam", "otp"):
        out["tasks"], imp = [], 0
    db.run("UPDATE items SET category=?, importance=?, summary=?, processed=1 WHERE id=?",
           (out.get("category"), imp, out.get("summary"), it["id"]))
    for t in out.get("tasks") or []:
        if isinstance(t, dict) and t.get("title"):
            db.run("INSERT INTO tasks(ts,title,due,kind,source_item) VALUES (?,?,?,?,?)",
                   (time.time(), t["title"], t.get("due", ""), t.get("kind", "todo"), it["id"]))
    for f in out.get("facts") or []:
        if isinstance(f, str) and f and f.lower() not in ("none", "n/a"):
            db.run("INSERT INTO memories(ts,fact,source_item) VALUES (?,?,?)", (time.time(), f, it["id"]))
    if it["person"]:
        db.run("""INSERT INTO people(name,relation,last_seen,msg_count) VALUES (?,?,?,1)
                  ON CONFLICT(name) DO UPDATE SET last_seen=excluded.last_seen, msg_count=msg_count+1,
                  relation=COALESCE(NULLIF(excluded.relation,''),relation)""",
               (it["person"], out.get("relation", ""), it["ts"]))
    if imp >= 4 and out.get("category") in ("needs_reply", "task", "event", "promise"):
        db.notify(f"⚡ {it['person'] or it['app']}", out.get("summary") or it["text"][:120], "urgent", str(it["id"]))
    if out.get("category") == "needs_reply" and imp >= 4 and it["direction"] == "in" and it["person"]:
        draft = llm.chat([{"role": "user", "content": "Write the short reply the user would send to this message, in the "
                           f"same language and tone. Never promise anything or invent facts; if unsure, keep it open.\n"
                           f"{it['person']}: \"\"\"{it['text'][:1000]}\"\"\"\nReturn only the reply."}])
        if draft.strip():
            if it["source"] == "gmail":
                raw = json.loads(it["raw"] or "{}")
                tools.draft_email(raw.get("from") or it["person"], "Re: " + raw.get("subject", ""), draft.strip())
            else:
                tools.draft_reply(it["person"], draft.strip())


def process_new(limit=25):
    items = db.rows("SELECT * FROM items WHERE processed=0 ORDER BY ts LIMIT ?", (limit,))
    now = datetime.datetime.now().strftime("%a %Y-%m-%d %H:%M")
    r = rules()
    for it in items:
        try:
            _process_one(it, now, r)
        except httpx.HTTPError:  # model is down or busy: keep the rest for the next tick
            traceback.print_exc()
            break
        except Exception:        # a bad answer for this one message: file it and move on
            traceback.print_exc()
            db.run("UPDATE items SET category='fyi', importance=1, processed=1 WHERE id=?", (it["id"],))
    return len(items)


def due_reminders():
    now = datetime.datetime.now()
    for t in db.rows("SELECT * FROM tasks WHERE status='open' AND notified=0 AND due!=''"):
        try:
            due = datetime.datetime.fromisoformat(t["due"])
        except ValueError:
            continue
        if due - now <= datetime.timedelta(minutes=30):
            db.notify("⏰ " + t["title"], f"Due {t['due']}", "reminder", str(t["id"]))
            db.run("UPDATE tasks SET notified=1 WHERE id=?", (t["id"],))


def event_alerts():
    """Tells the user when to leave for anything with a place to get to."""
    now = datetime.datetime.now()
    ahead = (now + datetime.timedelta(hours=4)).strftime("%Y-%m-%d %H:%M")
    for e in db.rows("SELECT * FROM calendar WHERE alerted=0 AND location!='' AND starts>=? AND starts<=?",
                     (now.strftime("%Y-%m-%d %H:%M"), ahead)):
        starts = datetime.datetime.fromisoformat(e["starts"])
        drive = maps.minutes_to(e["location"], f"route_{e['id']}")
        if drive is None:
            warn_at, body = starts - datetime.timedelta(hours=1), f"At {e['starts'][11:]}, {e['location']}"
        else:
            leave = starts - datetime.timedelta(minutes=drive + 15)
            warn_at = leave - datetime.timedelta(minutes=10)
            body = f"Leave by {leave:%H:%M}: about {drive} min drive to {e['location']}, without live traffic."
        if now >= warn_at:
            db.notify(f"🗓 {e['title']}", body, "reminder", e["id"])
            db.run("UPDATE calendar SET alerted=1 WHERE id=?", (e["id"],))


def place_reminders():
    here = maps._here()
    if not here:
        return
    for r in db.rows("SELECT * FROM place_reminders"):
        if maps.metres(here["lat"], here["lon"], r["lat"], r["lon"]) <= r["radius_m"]:
            db.notify("📍 " + r["text"][:60], f"You're at {r['place'].split(',')[0]}. {r['text']}", "reminder")
            db.run("DELETE FROM place_reminders WHERE id=?", (r["id"],))


def _once(key):
    """True the first time a key is seen, so nothing nags twice."""
    if db.get(key):
        return False
    db.put(key, "1")
    return True


def calendar_clashes():
    now = datetime.datetime.now()
    rows = db.rows("SELECT id,title,starts,ends FROM calendar WHERE starts>=? AND starts<=? ORDER BY starts",
                   (now.strftime("%Y-%m-%d %H:%M"), (now + datetime.timedelta(days=7)).strftime("%Y-%m-%d %H:%M")))
    for a, b in zip(rows, rows[1:]):
        if b["starts"] < a["ends"] and _once(f"clash_{a['id']}_{b['id']}"):
            db.notify("🗓 Calendar clash", f"{a['title']} and {b['title']} overlap on {a['starts'][:10]} at {b['starts'][11:]}.", "reminder")


def overdue_replies():
    now = time.time()
    for i in db.rows("""SELECT id,person,summary FROM items i WHERE category='needs_reply' AND ts<? AND ts>?
                        AND NOT EXISTS (SELECT 1 FROM items o WHERE o.person=i.person AND o.direction='out' AND o.ts>i.ts) LIMIT 10""",
                     (now - 86400, now - 3 * 86400)):
        if _once(f"nudge_{i['id']}"):
            db.notify(f"💬 Still waiting: {i['person']}", i["summary"] or "They're waiting for your reply.", "reminder", str(i["id"]))


def watch_inbox():
    """New mail goes through the same understanding as any message."""
    if not gmail.connected():
        return
    found = gmail.search_mail("in:inbox newer_than:1d -category:promotions -category:social", 15)
    for e in found.get("emails", []):
        if _once(f"mail_{e['id']}"):
            db.run("INSERT INTO items(ts,source,app,person,direction,text,raw) VALUES (?,?,?,?,?,?,?)",
                   (time.time(), "gmail", "gmail", e["from"], "in", f"{e['subject']}. {e['snippet']}",
                    db.dumps({"from": e["from"], "subject": e["subject"], "id": e["id"]})))


def dropped_threads():
    if not gmail.connected():
        return
    for t in gmail.dropped():
        if _once(f"thread_{t['thread']}"):
            db.notify("📧 No reply yet", f"{t['to']} hasn't answered \"{t['subject']}\".", "reminder", t["thread"])


def subscription_audit():
    week = datetime.date.today().strftime("%G-%V")
    if not gmail.connected() or not _once(f"subs_{week}"):
        return
    found = gmail.search_mail("subject:(subscription OR renewal OR renews) newer_than:60d", 20).get("emails", [])
    if not found:
        return
    text = llm.chat([{"role": "user", "content": "From these emails, list services the user is paying for or about to renew, "
                      "and any that look forgotten. Only use this data. If there are none, reply NOTHING.\n"
                      + json.dumps(found, ensure_ascii=False)}], smart=True)
    if text and not text.strip().upper().startswith("NOTHING"):
        db.notify("🔁 Your subscriptions", text, "brief")


def chase():
    if time.time() - float(db.get("last_chase", "0")) < 1800:
        return
    db.put("last_chase", str(time.time()))
    for step in (calendar_clashes, overdue_replies, watch_inbox, dropped_threads, subscription_audit):
        _safely(step)


def run_watches():
    for w in db.rows("SELECT * FROM watches WHERE next_run<=?", (time.time(),)):
        db.run("UPDATE watches SET next_run=? WHERE id=?", (time.time() + w["every_hours"] * 3600, w["id"]))
        agents.create(w["goal"] + "\nIf nothing is new or worth telling the user, finish with exactly NOTHING.", origin="brain")


def _today():
    return db.rows("SELECT title,starts,location FROM calendar WHERE substr(starts,1,10)<=? AND ends>=? ORDER BY starts LIMIT 15",
                   ((datetime.date.today() + datetime.timedelta(days=1)).isoformat(),
                    datetime.datetime.now().strftime("%Y-%m-%d %H:%M")))


def brief(kind="morning"):
    since = time.time() - 24 * 3600
    imp = db.rows("SELECT app,person,category,summary FROM items WHERE ts>? AND importance>=3 ORDER BY importance DESC LIMIT 30", (since,))
    unreplied = db.rows("""SELECT person, summary FROM items i WHERE category='needs_reply' AND ts>?
                           AND NOT EXISTS (SELECT 1 FROM items o WHERE o.person=i.person AND o.direction='out' AND o.ts>i.ts)
                           LIMIT 15""", (time.time() - 3 * 86400,))
    tasks = db.rows("SELECT title,due,kind FROM tasks WHERE status='open' ORDER BY due LIMIT 20")
    agent_res = db.rows("SELECT goal,status,result FROM agents WHERE ts>? AND parent IS NULL", (since,))
    text = llm.chat([{"role": "user", "content": f"""Write the user's {kind} brief. Warm, short, like a sharp personal chief of staff.
Sections: Top 3 things that matter, Calendar, People waiting for a reply, Tasks/promises due, What your agents did. Skip empty sections.
Data:\nIMPORTANT: {json.dumps(imp, ensure_ascii=False)}\nUNREPLIED: {json.dumps(unreplied, ensure_ascii=False)}
CALENDAR: {json.dumps(_today(), ensure_ascii=False)}\nTASKS: {json.dumps(tasks, ensure_ascii=False)}\nAGENTS: {json.dumps(agent_res, ensure_ascii=False)}"""}], smart=True)
    db.notify(f"☀️ {kind.title()} brief" if kind == "morning" else "🌙 Evening wrap-up", text, "brief")
    return text


PROACTIVE = """You are the user's personal assistant brain. Now: {now}.
Recent important items: {items}
Calendar soon: {calendar}
Open tasks: {tasks}
Already running/proposed agent goals: {goals}
Is there something you should proactively work on for the user (research, planning, preparing)?
Only propose clearly useful goals, never booking/paying/sending. Return JSON:
{{"goals": [{{"goal": "...", "why": "..."}}]}}  (0-2 goals; empty list is fine)"""


def proactive():
    items = db.rows("SELECT person,summary FROM items WHERE ts>? AND importance>=3 LIMIT 20", (time.time() - 86400,))
    if not items:
        return
    tasks = db.rows("SELECT title,due FROM tasks WHERE status='open' LIMIT 20")
    goals = [a["goal"] for a in db.rows("SELECT goal FROM agents WHERE ts>?", (time.time() - 7 * 86400,))]
    out = llm.chat([{"role": "user", "content": PROACTIVE.format(
        now=datetime.datetime.now().strftime("%a %Y-%m-%d %H:%M"),
        calendar=json.dumps(_today(), ensure_ascii=False), items=json.dumps(items, ensure_ascii=False), tasks=json.dumps(tasks, ensure_ascii=False),
        goals=json.dumps(goals, ensure_ascii=False))}], smart=True, as_json=True)
    for g in (out.get("goals") or [])[:2]:
        if not (isinstance(g, dict) and g.get("goal")):
            continue
        aid = agents.create(g["goal"], origin="brain", status="proposed")
        db.notify("💡 Idea", f"{g['goal']}\nWhy: {g.get('why','')}\nApprove to start.", "proposal", str(aid))


def refresh_memory():
    """Once a day, compress raw history into layers a small model can actually read."""
    today = str(datetime.date.today())
    if db.get("profile_date") == today:
        return
    facts = [m["fact"] for m in db.rows("SELECT fact FROM memories ORDER BY id DESC LIMIT 150")]
    recent = db.rows("SELECT person,summary FROM items WHERE ts>? AND importance>=2 ORDER BY importance DESC LIMIT 40",
                     (time.time() - 7 * 86400,))
    if not facts and not recent:
        return
    text = llm.chat([{"role": "user", "content": f"""Update this profile of the user. Under 150 words. Who they are, key people, what is going on now, what matters to them.
Only use the data below; never guess.
Current profile: {db.get("profile", "none")}
Known facts: {json.dumps(facts, ensure_ascii=False)}
This week: {json.dumps(recent, ensure_ascii=False)}"""}], smart=True)
    db.put("profile", text)
    for p in db.rows("SELECT name FROM people ORDER BY last_seen DESC LIMIT 8"):
        msgs = [m["summary"] for m in db.rows(
            "SELECT summary FROM items WHERE person=? AND summary IS NOT NULL ORDER BY ts DESC LIMIT 15", (p["name"],))]
        if msgs:
            note = llm.chat([{"role": "user", "content": "In 2 sentences: who is this person to the user and what is "
                              f"open between them? Only use this data. {p['name']}: {json.dumps(msgs, ensure_ascii=False)}"}])
            db.run("UPDATE people SET notes=? WHERE name=?", (note, p["name"]))
    db.put("profile_date", today)


_last = {"brief": None, "proactive": 0.0}


def _safely(step, *args):
    try:
        return step(*args)
    except Exception:  # one failing step must not stop the others
        traceback.print_exc()


def _start_queued():
    for a in db.rows("SELECT id FROM agents WHERE status='queued'"):
        agents.start(a["id"])


def tick():
    n = _safely(process_new)
    _safely(google_calendar.sync_if_due)
    _safely(due_reminders)
    _safely(event_alerts)
    _safely(place_reminders)
    _safely(chase)
    _safely(run_watches)
    _safely(refresh_memory)
    _safely(_start_queued)
    now = datetime.datetime.now()
    for hour, kind in ((21, "evening"),):  # the user wants one summary a day at most, in the evening
        key = f"brief-{now.date()}-{kind}"
        if now.hour == hour and not db.get(key):  # stored, so a restart cannot send it twice
            db.put(key, "1")
            _safely(brief, kind)
    if time.time() - _last["proactive"] > 3 * 3600:
        _last["proactive"] = time.time()
        _safely(proactive)
    return n


_REPEAT = re.compile(r"(?i)\b(every\s?(day|morning|evening|night|week|hour|\d+\s?hours?)|daily|weekly|hourly|keep\s(an\s)?eye|"
                     r"keep\s(checking|watching|tracking)|let me know when|tell me (when|whenever)|notify me (when|if)|"
                     r"alert me (when|if))\b")


def repeat_hours(text):
    """How often a request wants checking, or None if it isn't a repeating request."""
    m = _REPEAT.search(text)
    if not m or not re.search(r"(?i)\b(check|watch|track|monitor|keep an eye|tell me|let me know|notify|alert|update me|look)", text):
        return None
    t = text.lower()
    if re.search(r"\bevery\s?hour|\bhourly", t):
        return 1
    n = re.search(r"\bevery\s?(\d+)\s?hours?", t)
    if n:
        return max(1, int(n.group(1)))
    if re.search(r"\bevery\s?week|\bweekly", t):
        return 168
    if re.search(r"(when|if)\b.*\b(sale|drop|price|available|open|release|launch|tickets?)", t):
        return 6
    return 24


def ask(question):
    """Chat: the user asks anything; it's handled by an agent with memory, phone data and web tools."""
    db.run("UPDATE agents SET status='done', question=NULL WHERE origin='chat' AND status='waiting'")
    history = json.loads(db.get("chat_history", "[]"))
    context = "\n".join(f"{h['who']}: {h['text']}" for h in history)
    goal = (f"Answer the user's message. For greetings, general questions, writing, or requests that need no personal data, "
            "answer directly without calling a tool. Only check tools when the answer genuinely depends on the user's "
            "calendar, tasks, people, messages, location, or current information.\n"
            "Never hand the user's question back to them, and don't ask what they mean when you can look it up. "
            "For questions about their day or plan, check the calendar AND open tasks, people waiting on a reply and reminders, "
            "then answer from that. If you really find nothing, say so in one line and suggest one useful next step.\n")
    if context:
        goal += f"Conversation so far:\n{context}\n"
    hours = repeat_hours(question)
    if hours:  # set up the repeating job here, not left to the model's judgement
        made = tools.watch(question, hours)
        goal += (f"A repeating job was already set up for this ({made.get('status') or made.get('error')}). "
                 "Finish straight away: tell the user in one or two sentences that it's set up, how often it checks, and that "
                 "the first check is already running in the background. Don't search now and don't call watch again.\n")
    aid = agents.create(goal + f"The user's message: {question}", origin="chat", status="created")
    db.run("UPDATE agents SET status='running' WHERE id=?", (aid,))
    agents._loop(aid)
    out = db.rows("SELECT id,status,result,question FROM agents WHERE id=?", (aid,))[0]
    history += [{"who": "User", "text": question[:300]}, {"who": "Chacha", "text": str(out["question"] or out["result"] or "")[:300]}]
    db.put("chat_history", db.dumps(history[-12:]))
    for who, text in (("in", question), ("out", out["question"] or out["result"] or "")):  # searchable forever
        db.run("INSERT INTO items(ts,source,app,person,direction,text,category,processed) VALUES (?,?,?,?,?,?,?,1)",
               (time.time(), "chat", "chat", "Chacha", who, text, "fyi"))
    return out
