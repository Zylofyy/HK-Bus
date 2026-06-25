import concurrent.futures
import json
import math
import re
import sqlite3
import sys
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT = ASSETS / "route_graph.db"
MAIN = ROOT / "app" / "src" / "main" / "java" / "com" / "example" / "hkbus" / "MainActivity.java"
MTR = ROOT / "app" / "src" / "main" / "java" / "com" / "example" / "hkbus" / "MtrBusStops.java"
UA = "HK-Bus-RouteGraphBuilder/1.0"
CONNECT_TIMEOUT = 18
READ_TIMEOUT = 18


def fetch_json(url):
    req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": UA})
    with urllib.request.urlopen(req, timeout=READ_TIMEOUT) as resp:
        return json.loads(resp.read().decode("utf-8"))


def post_json(url, body):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Accept": "application/json", "Content-Type": "application/json; charset=UTF-8", "User-Agent": UA})
    with urllib.request.urlopen(req, timeout=READ_TIMEOUT) as resp:
        return json.loads(resp.read().decode("utf-8"))


def array_from_data(root, key="data"):
    data = root.get(key, [])
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        return [v for v in data.values() if isinstance(v, dict)]
    return []


def best(o, *keys):
    for key in keys:
        value = str(o.get(key, "") or "").strip()
        if value:
            return value
    return ""


def enc(value):
    return urllib.parse.quote(str(value), safe="")


def fnum(value):
    try:
        return float(value)
    except Exception:
        return 0.0


def transfer_key(lat, lon):
    if not lat or not lon:
        return "0:0"
    return f"{round(lat * 1000)}:{round(lon * 1000)}"


def seq_from_mtr_id(stop_id):
    m = re.search(r"-(?:n)?[UD](\d+)$", stop_id)
    return int(m.group(1)) if m else 0


def route_from_mtr_id(stop_id):
    return stop_id.split("-", 1)[0]


def dir_from_mtr_id(stop_id):
    m = re.search(r"-(?:n)?([UD])\d+$", stop_id)
    return m.group(1) if m else ""


def split_ends(name):
    for token in (" > ", " to ", " - "):
        if token in name:
            a, b = name.split(token, 1)
            return a.strip(), b.strip()
    return name.strip(), ""

def ctb_stop(stop_id, cache, lock):
    if not stop_id:
        return None
    with lock:
        cached = cache.get(stop_id)
        if cached is not None:
            return cached
    stop = None
    try:
        data = fetch_json(f"https://rt.data.gov.hk/v2/transport/citybus/stop/{enc(stop_id)}").get("data", {})
        if isinstance(data, dict) and data.get("stop"):
            stop = {"id": str(data.get("stop")), "name": best(data, "name_en", "name_tc"), "lat": fnum(data.get("lat")), "lon": fnum(data.get("long"))}
    except Exception:
        stop = None
    with lock:
        cache[stop_id] = stop
    return stop


def parse_mtr_stops():
    text = MTR.read_text(encoding="utf-8")
    pattern = re.compile(r'MAP\.put\("([^"]+)", new Info\("[^"]+", "([^"]*)", ([\d.\-]+), ([\d.\-]+)\)\);')
    stops = []
    for stop_id, name, lat, lon in pattern.findall(text):
        stops.append({"id": stop_id, "name": name, "lat": fnum(lat), "lon": fnum(lon)})
    return stops


def parse_mtr_routes():
    text = MAIN.read_text(encoding="utf-8")
    block = re.search(r'private void addMtrRoutes\(List<Route> out\) \{[\s\S]*?String\[\]\[\] routes = \{([\s\S]*?)\};', text)
    routes = []
    if not block:
        return routes
    for route, orig, dest in re.findall(r'\{"([^"]+)", "([^"]*)", "([^"]*)"\}', block.group(1)):
        routes.append({"operator": "MTR", "route": route, "service_type": "1", "orig": orig, "dest": dest})
    return routes


def add_pattern(patterns, pattern_stops, operator, route, direction, service_type, orig, dest, stops):
    usable = [s for s in stops if s.get("lat") and s.get("lon")]
    if len(usable) < 2:
        return
    pid = len(patterns) + 1
    patterns.append({"pattern_id": pid, "operator": operator, "route": route, "dir": direction, "service_type": str(service_type), "orig": orig, "dest": dest})
    for seq, stop in enumerate(usable, 1):
        lat = fnum(stop.get("lat"))
        lon = fnum(stop.get("lon"))
        pattern_stops.append({
            "pattern_id": pid,
            "seq": seq,
            "stop_id": str(stop.get("id", "")),
            "name": str(stop.get("name", "")),
            "lat": lat,
            "lon": lon,
            "transfer_key": transfer_key(lat, lon),
        })


def build_graph():
    print("Loading operator stop and route lists...", flush=True)
    kmb_stops = {}
    for o in array_from_data(fetch_json("https://data.etabus.gov.hk/v1/transport/kmb/stop")):
        kmb_stops[str(o.get("stop"))] = {"id": str(o.get("stop")), "name": best(o, "name_en", "name_tc"), "lat": fnum(o.get("lat")), "lon": fnum(o.get("long"))}

    ctb_stops = {}
    for o in array_from_data(fetch_json("https://rt.data.gov.hk/v2/transport/citybus/stop/CTB")):
        ctb_stops[str(o.get("stop"))] = {"id": str(o.get("stop")), "name": best(o, "name_en", "name_tc"), "lat": fnum(o.get("lat")), "lon": fnum(o.get("long"))}
    ctb_stop_lock = threading.Lock()

    mtr_stops = parse_mtr_stops()
    mtr_by_route = {}
    for stop in mtr_stops:
        mtr_by_route.setdefault(route_from_mtr_id(stop["id"]), []).append(stop)

    kmb_routes = []
    seen = set()
    for o in array_from_data(fetch_json("https://data.etabus.gov.hk/v1/transport/kmb/route/")):
        route = str(o.get("route", ""))
        service = str(o.get("service_type", "1") or "1")
        orig = best(o, "orig_en", "orig_tc")
        dest = best(o, "dest_en", "dest_tc")
        key = (route, service, orig, dest)
        if route and key not in seen:
            seen.add(key)
            kmb_routes.append({"operator": "KMB", "route": route, "service_type": service, "orig": orig, "dest": dest})

    ctb_routes = []
    seen = set()
    for o in array_from_data(fetch_json("https://rt.data.gov.hk/v2/transport/citybus/route/CTB")):
        route = str(o.get("route", ""))
        orig = best(o, "orig_en", "orig_tc")
        dest = best(o, "dest_en", "dest_tc")
        key = (route, orig, dest)
        if route and key not in seen:
            seen.add(key)
            ctb_routes.append({"operator": "CTB", "route": route, "service_type": "1", "orig": orig, "dest": dest})

    nlb_routes = []
    for o in fetch_json("https://rt.data.gov.hk/v2/transport/nlb/route.php?action=list").get("routes", []):
        route_id = str(o.get("routeId", ""))
        route_no = str(o.get("routeNo", ""))
        orig, dest = split_ends(str(o.get("routeName_e", "")))
        if route_id and route_no:
            nlb_routes.append({"operator": "NLB", "route": route_no, "service_type": route_id, "orig": orig, "dest": dest})

    mtr_routes = parse_mtr_routes()
    patterns = []
    pattern_stops = []

    def safe_call(fn, label):
        try:
            fn()
        except Exception as exc:
            print(f"WARN {label}: {exc}", flush=True)

    print(f"Fetching route-stop patterns: KMB={len(kmb_routes)} CTB={len(ctb_routes)} NLB={len(nlb_routes)} MTR={len(mtr_routes)}", flush=True)

    def kmb_task(route):
        local = []
        for direction, orig, dest in (("outbound", route["orig"], route["dest"]), ("inbound", route["dest"], route["orig"])):
            url = f"https://data.etabus.gov.hk/v1/transport/kmb/route-stop/{enc(route['route'])}/{direction}/{enc(route['service_type'])}"
            arr = array_from_data(fetch_json(url))
            stops = []
            for i, rs in enumerate(sorted(arr, key=lambda x: int(x.get("seq", 9999))), 1):
                base = kmb_stops.get(str(rs.get("stop")))
                if base:
                    stops.append(base)
            local.append((route["operator"], route["route"], direction, route["service_type"], orig, dest, stops))
        return local

    def ctb_task(route):
        local = []
        for direction, orig, dest in (("outbound", route["orig"], route["dest"]), ("inbound", route["dest"], route["orig"])):
            url = f"https://rt.data.gov.hk/v2/transport/citybus/route-stop/CTB/{enc(route['route'])}/{direction}"
            arr = array_from_data(fetch_json(url))
            stops = []
            for rs in sorted(arr, key=lambda x: int(x.get("seq", 9999))):
                stop_id = str(rs.get("stop"))
                base = ctb_stops.get(stop_id) or ctb_stop(stop_id, ctb_stops, ctb_stop_lock)
                if base:
                    stops.append(base)
            local.append((route["operator"], route["route"], direction, route["service_type"], orig, dest, stops))
        return local

    def nlb_task(route):
        url = f"https://rt.data.gov.hk/v2/transport/nlb/stop.php?action=list&routeId={enc(route['service_type'])}"
        arr = fetch_json(url).get("stops", [])
        stops = []
        for o in arr:
            stops.append({"id": str(o.get("stopId", "")), "name": best(o, "stopName_e", "stopName_c"), "lat": fnum(o.get("latitude")), "lon": fnum(o.get("longitude"))})
        return [(route["operator"], route["route"], "outbound", route["service_type"], route["orig"], route["dest"], stops)]

    def mtr_patterns():
        for route in mtr_routes:
            route_stops = mtr_by_route.get(route["route"], [])
            for code, direction, orig, dest in (("U", "outbound", route["orig"], route["dest"]), ("D", "inbound", route["dest"], route["orig"])):
                stops = sorted([s for s in route_stops if dir_from_mtr_id(s["id"]) == code], key=lambda s: seq_from_mtr_id(s["id"]))
                if stops:
                    yield (route["operator"], route["route"], direction, route["service_type"], orig, dest, stops)

    tasks = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as ex:
        for route in kmb_routes:
            tasks.append(ex.submit(kmb_task, route))
        for route in ctb_routes:
            tasks.append(ex.submit(ctb_task, route))
        for route in nlb_routes:
            tasks.append(ex.submit(nlb_task, route))
        total = len(tasks)
        done = 0
        for future in concurrent.futures.as_completed(tasks):
            done += 1
            try:
                for item in future.result():
                    add_pattern(patterns, pattern_stops, *item)
            except Exception as exc:
                print(f"WARN route pattern failed: {exc}", flush=True)
            if done % 50 == 0 or done == total:
                print(f"  {done}/{total} route pattern jobs", flush=True)

    for item in mtr_patterns():
        add_pattern(patterns, pattern_stops, *item)

    return patterns, pattern_stops


def write_db(patterns, pattern_stops):
    ASSETS.mkdir(parents=True, exist_ok=True)
    tmp = OUT.with_suffix(".tmp")
    if tmp.exists():
        tmp.unlink()
    db = sqlite3.connect(tmp)
    cur = db.cursor()
    cur.execute("PRAGMA journal_mode=OFF")
    cur.execute("PRAGMA synchronous=OFF")
    cur.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    cur.execute("CREATE TABLE route_patterns(pattern_id INTEGER PRIMARY KEY, operator TEXT NOT NULL, route TEXT NOT NULL, dir TEXT NOT NULL, service_type TEXT NOT NULL, orig TEXT NOT NULL, dest TEXT NOT NULL)")
    cur.execute("CREATE TABLE pattern_stops(pattern_id INTEGER NOT NULL, seq INTEGER NOT NULL, stop_id TEXT NOT NULL, name TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, transfer_key TEXT NOT NULL)")
    cur.execute("CREATE TABLE physical_stops(transfer_key TEXT PRIMARY KEY, name TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL)")
    cur.executemany("INSERT INTO route_patterns VALUES(:pattern_id,:operator,:route,:dir,:service_type,:orig,:dest)", patterns)
    cur.executemany("INSERT INTO pattern_stops VALUES(:pattern_id,:seq,:stop_id,:name,:lat,:lon,:transfer_key)", pattern_stops)
    physical = {}
    for s in pattern_stops:
        key = s["transfer_key"]
        if key == "0:0":
            continue
        if key not in physical or len(s["name"]) < len(physical[key]["name"]):
            physical[key] = {"transfer_key": key, "name": s["name"], "lat": s["lat"], "lon": s["lon"]}
    cur.executemany("INSERT INTO physical_stops VALUES(:transfer_key,:name,:lat,:lon)", list(physical.values()))
    cur.executemany("INSERT INTO meta VALUES(?,?)", [
        ("graph_version", "20260626-1"),
        ("generated_at", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
        ("patterns", str(len(patterns))),
        ("pattern_stops", str(len(pattern_stops))),
        ("physical_stops", str(len(physical))),
    ])
    cur.execute("CREATE INDEX idx_pattern_stops_pattern_seq ON pattern_stops(pattern_id, seq)")
    cur.execute("CREATE INDEX idx_pattern_stops_transfer ON pattern_stops(transfer_key)")
    cur.execute("CREATE INDEX idx_physical_name ON physical_stops(name)")
    db.commit()
    db.close()
    if OUT.exists():
        OUT.unlink()
    tmp.replace(OUT)
    print(f"Wrote {OUT} patterns={len(patterns)} pattern_stops={len(pattern_stops)} physical_stops={len(physical)} size={OUT.stat().st_size}", flush=True)


def main():
    patterns, pattern_stops = build_graph()
    if not patterns or not pattern_stops:
        raise SystemExit("No route graph data was generated")
    write_db(patterns, pattern_stops)


if __name__ == "__main__":
    main()
