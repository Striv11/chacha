"""Google Calendar sync for users who do not connect an Android phone."""
import datetime, time
from urllib.parse import quote

import httpx

from . import db, gmail

API = "https://www.googleapis.com/calendar/v3"


def _iso(value: str, all_day=False):
    if not value:
        return ""
    if all_day:
        return value + " 00:00"
    parsed = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo:
        parsed = parsed.astimezone()
    return parsed.strftime("%Y-%m-%d %H:%M")


def _event_row(event):
    start = event.get("start") or {}
    end = event.get("end") or {}
    all_day = "date" in start
    begins = _iso(start.get("date") or start.get("dateTime", ""), all_day)
    finishes = _iso(end.get("date") or end.get("dateTime", ""), "date" in end)
    return (
        "google:" + str(event.get("id", "")),
        str(event.get("summary") or "Untitled event")[:300],
        begins,
        finishes or begins,
        str(event.get("location") or "")[:500],
        "google",
    )


def sync(days_back=1, days_ahead=45):
    if not gmail.connected():
        return {"error": "Google is not connected."}
    now = datetime.datetime.now(datetime.timezone.utc)
    params = {
        "timeMin": (now - datetime.timedelta(days=days_back)).isoformat(),
        "timeMax": (now + datetime.timedelta(days=days_ahead)).isoformat(),
        "singleEvents": "true",
        "orderBy": "startTime",
        "maxResults": 250,
    }
    response = httpx.get(
        API + "/calendars/" + quote("primary", safe="") + "/events",
        headers={"Authorization": f"Bearer {gmail._access()}"},
        params=params,
        timeout=30,
    )
    response.raise_for_status()
    rows = [_event_row(event) for event in response.json().get("items", []) if event.get("status") != "cancelled"]
    rows = [row for row in rows if row[0] != "google:" and row[2]]
    with db.tx() as connection:
        connection.execute("DELETE FROM calendar WHERE source='google'")
        connection.executemany(
            "INSERT OR REPLACE INTO calendar(id,title,starts,ends,location,source) VALUES (?,?,?,?,?,?)",
            rows,
        )
    stamp = str(time.time())
    db.put("synced_calendar", stamp)
    db.put("google_calendar_sync", stamp)
    return {"ok": True, "events": len(rows), "synced_at": stamp}


def sync_if_due(every_seconds=900):
    last = float(db.get("google_calendar_sync", "0") or 0)
    if gmail.connected() and time.time() - last >= every_seconds:
        return sync()
    return {"ok": True, "skipped": True}
