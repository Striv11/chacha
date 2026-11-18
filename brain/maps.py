"""Free OpenStreetMap place search and driving times. No key needed, and no live traffic.
Used only when asked or for events with a location. Sends the place and coordinates to those public services."""
import json, threading, time
import httpx
from . import db

_HEADERS = {"User-Agent": "pocket-assistant/0.1"}
_lock = threading.Lock()
_last = [0.0]


def _places(query, limit=1, bounded=False):
    """Nominatim allows one request a second, so calls take turns."""
    params = {"q": query, "format": "json", "limit": limit}
    here = _here()
    if here:
        params["viewbox"] = f"{here['lon'] - 1},{here['lat'] + 1},{here['lon'] + 1},{here['lat'] - 1}"
        params["bounded"] = int(bounded)
    with _lock:
        time.sleep(max(0.0, _last[0] + 1.1 - time.time()))
        _last[0] = time.time()
        r = httpx.get("https://nominatim.openstreetmap.org/search", params=params, headers=_HEADERS, timeout=15)
    r.raise_for_status()
    return r.json()


def _here():
    raw = db.get("location")
    return json.loads(raw) if raw else None


def _drive(frm, lat, lon):
    r = httpx.get(f"https://router.project-osrm.org/route/v1/driving/{frm['lon']},{frm['lat']};{lon},{lat}",
                  params={"overview": "false"}, headers=_HEADERS, timeout=15)
    r.raise_for_status()
    route = r.json()["routes"][0]
    return round(route["duration"] / 60), round(route["distance"] / 1000, 1)


def route_to(destination: str):
    here = _here()
    if not here:
        return {"error": "Location has not synced from the phone yet. Tell the user."}
    try:
        found = _places(destination)
        if not found:
            return {"error": f"Couldn't find {destination} on the map."}
        minutes, km = _drive(here, found[0]["lat"], found[0]["lon"])
    except (httpx.HTTPError, KeyError, IndexError, ValueError) as e:
        return {"error": f"Maps failed: {e}"}
    return {"place": found[0]["display_name"], "drive_minutes": minutes, "km": km,
            "note": "Estimate without live traffic. Say so."}


def find_place(query: str):
    try:
        found = _places(query, limit=5)
    except (httpx.HTTPError, ValueError) as e:
        return {"error": f"Maps failed: {e}"}
    if not found:
        return {"places": [], "note": "OpenStreetMap found nothing. Try a more specific name."}
    return {"places": [p["display_name"] for p in found]}


def locate(query: str):
    """(lat, lon, name) for a place, preferring ones near the phone; None if not found."""
    found = _places(query, bounded=bool(_here())) or (_places(query) if _here() else [])
    return (float(found[0]["lat"]), float(found[0]["lon"]), found[0]["display_name"]) if found else None


def metres(a_lat, a_lon, b_lat, b_lon):
    import math
    p = math.pi / 180
    h = (math.sin((b_lat - a_lat) * p / 2) ** 2 +
         math.cos(a_lat * p) * math.cos(b_lat * p) * math.sin((b_lon - a_lon) * p / 2) ** 2)
    return 12_742_000 * math.asin(math.sqrt(h))


def minutes_to(location: str, cache_key: str):
    """Driving minutes to an event, or None if it can't be worked out sensibly nearby."""
    here = _here()
    if not here:
        return None
    raw = db.get(cache_key)
    if raw:
        cached = json.loads(raw)
        if time.time() - cached["t"] < 1800:
            return cached["m"]
    try:
        found = _places(location, bounded=True)
        minutes = _drive(here, found[0]["lat"], found[0]["lon"])[0] if found else None
    except (httpx.HTTPError, KeyError, IndexError, ValueError):
        return None
    if minutes is not None and minutes > 600:
        minutes = None
    db.put(cache_key, db.dumps({"t": time.time(), "m": minutes}))
    return minutes
