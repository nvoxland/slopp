#!/usr/bin/env python3
"""Drive slopp over MCP-over-HTTP — the daemon's endpoint, which is what
Claude Code's plugin entry talks to — and print each call's round-trip
wall: slopp's cost with no harness around it.

    bin/mcp-roundtrip.py <project-dir> [--rounds 3] [--calls calls.json]

Why this exists: a transcript's tool_use→tool_result span is the HARNESS's
view of a call, and it includes whatever the harness does around the call.
On 2026-09-03 every slopp call in an eval cell showed a ~1.3 s floor there
while this driver measured the same ops at 0.00–0.38 s against the same jar
and store — the gap was Claude Code's auto-mode permission classifier, not
slopp. Run this first whenever "slopp is slow" is the claim; it says which
side of the wire to look at.

To profile the daemon, attach Flight Recorder to its pid from the outside
(`jcmd $(python3 -c 'import json;print(json.load(open("$HOME/.slopp/daemon.json"))["pid"])') JFR.start …`)
and read the dump with `jfr print --events jdk.ExecutionSample out.jfr`.
`--calls` is a JSON list of {"tool": ..., "args": {...}}; the default plan
is read-only ops. A dev tool: the plugin itself needs no Python.
"""
import argparse
import json
import os
import sys
import time
import urllib.request

DEFAULT_PLAN = [
    ("store", {"op": "query_capabilities"}),
    ("read", {"op": "query_project"}),
    ("history", {"op": "report"}),
    ("read", {"op": "query_search", "pattern": "defn"}),
    ("orient", {"op": "session_brief"}),
    ("store", {"op": "query_cost"}),
]

ap = argparse.ArgumentParser()
ap.add_argument("dir")
ap.add_argument("--rounds", type=int, default=3)
ap.add_argument("--calls")
a = ap.parse_args()

plan = [(c["tool"], c.get("args", {})) for c in json.load(open(a.calls))] if a.calls else DEFAULT_PLAN
# the daemon's MCP endpoint, the project named by dir — exactly what the
# plugin's .mcp.json entry does; the daemon is yours to have started
port = os.environ.get("SLOPP_DAEMON_PORT") or "7357"
endpoint = f"http://127.0.0.1:{port}/api/projects/_/mcp"
project = os.path.realpath(a.dir)
session = None
n = [0]


def rpc(method, params=None, notify=False):
    global session
    n[0] += 1
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    if not notify:
        msg["id"] = n[0]
    h = {"Content-Type": "application/json", "X-Slopp-Dir": project}
    if session:
        h["Mcp-Session-Id"] = session
    t0 = time.time()
    req = urllib.request.Request(endpoint, data=json.dumps(msg).encode(), headers=h, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            sid = r.headers.get("Mcp-Session-Id")
            if sid:
                session = sid
            body = r.read().decode("utf-8")
    except urllib.error.URLError as e:
        raise SystemExit(f"no daemon at {endpoint} ({e}) — start one with `slopp daemon`")
    if notify or not body.strip():
        return None, time.time() - t0
    return json.loads(body), time.time() - t0


def call(tool, args):
    r, dt = rpc("tools/call", {"name": tool, "arguments": args})
    txt = r.get("result", {}).get("content", [{}])[0].get("text", "")
    return dt, len(txt)


_, dt = rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                           "clientInfo": {"name": "mcp-roundtrip", "version": "0"}})
print(f"initialize      {dt:6.2f}s")
rpc("notifications/initialized", notify=True)
r, dt = rpc("tools/list")
print(f"tools/list      {dt:6.2f}s ({len(r['result']['tools'])} tools)")
for _ in range(a.rounds):
    for tool, args in plan:
        dt, chars = call(tool, args)
        print(f"{dt:6.2f}s {tool}/{args.get('op', ''):20s} {chars:7d} chars")
req = urllib.request.Request(endpoint, headers={"Mcp-Session-Id": session, "X-Slopp-Dir": project},
                             method="DELETE")
urllib.request.urlopen(req, timeout=30).read()
print("session ended")
