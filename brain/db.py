import sqlite3, json, time
from contextlib import contextmanager
from .config import DB_PATH

SCHEMA = """
CREATE TABLE IF NOT EXISTS items (           -- everything the phone/laptop captures
  id INTEGER PRIMARY KEY, ts REAL, source TEXT, app TEXT, person TEXT,
  direction TEXT, text TEXT, raw TEXT,
  category TEXT, importance INTEGER, summary TEXT, processed INTEGER DEFAULT 0,
  resolved INTEGER DEFAULT 0
);
CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING fts5(text, person, summary, content='items', content_rowid='id');
CREATE TRIGGER IF NOT EXISTS items_ai AFTER INSERT ON items BEGIN
  INSERT INTO items_fts(rowid, text, person, summary) VALUES (new.id, new.text, new.person, new.summary);
END;
CREATE TRIGGER IF NOT EXISTS items_au AFTER UPDATE ON items BEGIN
  INSERT INTO items_fts(items_fts, rowid, text, person, summary) VALUES ('delete', old.id, old.text, old.person, old.summary);
  INSERT INTO items_fts(rowid, text, person, summary) VALUES (new.id, new.text, new.person, new.summary);
END;
CREATE TABLE IF NOT EXISTS people (
  name TEXT PRIMARY KEY, relation TEXT, notes TEXT, last_seen REAL, msg_count INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS memories (        -- durable facts about the user's life
  id INTEGER PRIMARY KEY, ts REAL, fact TEXT, source_item INTEGER
);
CREATE TABLE IF NOT EXISTS tasks (           -- todos, promises, reminders
  id INTEGER PRIMARY KEY, ts REAL, title TEXT, due TEXT, kind TEXT,
  status TEXT DEFAULT 'open', source_item INTEGER, notified INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS agents (
  id INTEGER PRIMARY KEY, ts REAL, parent INTEGER, goal TEXT, origin TEXT,
  status TEXT, steps INTEGER DEFAULT 0, log TEXT DEFAULT '[]', result TEXT,
  question TEXT
);
CREATE TABLE IF NOT EXISTS notifications (   -- outbox the phone pulls
  id INTEGER PRIMARY KEY, ts REAL, title TEXT, body TEXT, kind TEXT, ref TEXT, seen INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS cloud_log (       -- audit: every text that ever left the laptop
  id INTEGER PRIMARY KEY, ts REAL, text TEXT
);
CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE IF NOT EXISTS calendar (        -- replaced on every phone sync
  id TEXT PRIMARY KEY, title TEXT, starts TEXT, ends TEXT, location TEXT, alerted INTEGER DEFAULT 0,
  source TEXT DEFAULT 'phone'
);
CREATE TABLE IF NOT EXISTS contacts (name TEXT, number TEXT, PRIMARY KEY (name, number));
CREATE TABLE IF NOT EXISTS calls (ts REAL, name TEXT, number TEXT, kind TEXT, PRIMARY KEY (ts, number));
CREATE TABLE IF NOT EXISTS watches (         -- jobs Chacha keeps doing on its own
  id INTEGER PRIMARY KEY, goal TEXT, every_hours REAL, next_run REAL
);
CREATE TABLE IF NOT EXISTS place_reminders (  -- fire once when the phone comes within reach of a place
  id INTEGER PRIMARY KEY, text TEXT, place TEXT, lat REAL, lon REAL, radius_m REAL DEFAULT 250
);
CREATE TABLE IF NOT EXISTS actions (         -- things only the user's tap on the phone can carry out
  id INTEGER PRIMARY KEY, ts REAL, kind TEXT, summary TEXT, payload TEXT,
  status TEXT DEFAULT 'proposed', note TEXT
);
CREATE TABLE IF NOT EXISTS feedback (        -- user corrections -> learned rules
  id INTEGER PRIMARY KEY, ts REAL, rule TEXT
);
"""


def connect():
    c = sqlite3.connect(DB_PATH, timeout=30, check_same_thread=False)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA journal_mode=WAL")
    return c


@contextmanager
def tx():
    c = connect()
    try:
        yield c
        c.commit()
    finally:
        c.close()


def init():
    with tx() as c:
        c.executescript(SCHEMA)
        try:
            c.execute("ALTER TABLE agents ADD COLUMN world INTEGER DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        try:
            c.execute("ALTER TABLE calendar ADD COLUMN source TEXT DEFAULT 'phone'")
        except sqlite3.OperationalError:
            pass
        try:
            c.execute("ALTER TABLE items ADD COLUMN resolved INTEGER DEFAULT 0")
        except sqlite3.OperationalError:
            pass


def reset_stale():
    """Agents marked running when the server starts died with the last process."""
    run("UPDATE agents SET status='error', result='Interrupted by restart' WHERE status='running'")


def get(k, default=""):
    r = rows("SELECT v FROM kv WHERE k=?", (k,))
    return r[0]["v"] if r else default


def put(k, v):
    run("INSERT INTO kv(k,v) VALUES (?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, v))


def log_cloud(text):
    run("INSERT INTO cloud_log(ts,text) VALUES (?,?)", (time.time(), str(text)[:4000]))


def rows(sql, args=()):
    with tx() as c:
        return [dict(r) for r in c.execute(sql, args).fetchall()]


def run(sql, args=()):
    with tx() as c:
        return c.execute(sql, args).lastrowid


def notify(title, body, kind="info", ref=""):
    run("INSERT INTO notifications(ts,title,body,kind,ref) VALUES (?,?,?,?,?)",
        (time.time(), title, body, kind, ref))


def fts_query(text):
    words = [w for w in "".join(ch if ch.isalnum() else " " for ch in text).split() if len(w) > 2]
    return " OR ".join(f'"{w}"' for w in words[:12]) or '""'


def dumps(x):
    return json.dumps(x, ensure_ascii=False)
