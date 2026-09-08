#!/usr/bin/env python3
"""Drive slopp over RAW stdio — the plugin's pipe onto the daemon, which is
what Claude Code runs — and print each call's round-trip wall: slopp's cost
with no harness around it.

    bin/mcp-roundtrip.py <project-dir> [--rounds 3] [--calls calls.json]

Why this exists: a transcript's tool_use→tool_result span is the HARNESS's
view of a call, and it includes whatever the harness does around the call.
On 2026-09-03 every slopp call in an eval cell showed a ~1.3 s floor there
while this driver measured the same ops at 0.00–0.38 s against the same jar
and store — the gap was Claude Code's auto-mode permission classifier, not
slopp. Run this first whenever "slopp is slow" is the claim; it says which
side of the pipe to look at.

To profile the daemon, attach Flight Recorder to its pid from the outside
(`jcmd $(python3 -c 'import json;print(json.load(open("$HOME/.slopp/daemon.json"))["pid"])') JFR.start …`)
and read the dump with `jfr print --events jdk.ExecutionSample out.jfr`.
`--calls` is a JSON list of {"tool": ..., "args": {...}}; the default plan
is read-only ops.
"""
import argparse
import json
import os
import subprocess
import sys
import time

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
# the plugin's own entry: a pipe onto the machine's daemon, started on
# demand, the project named by the cwd — exactly what the harness runs
here = os.path.dirname(os.path.abspath(__file__))
cmd = ["python3", os.path.join(here, "..", "plugins", "slopp", "bin", "slopp-pipe.py")]
env = {k: v for k, v in os.environ.items() if k != "CLAUDE_PROJECT_DIR"}
p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=sys.stderr,
                     env=env, text=True, bufsize=1, cwd=a.dir)
n = [0]


def rpc(method, params=None, notify=False):
    n[0] += 1
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    if not notify:
        msg["id"] = n[0]
    t0 = time.time()
    p.stdin.write(json.dumps(msg) + "\n")
    p.stdin.flush()
    if notify:
        return None, 0.0
    while True:
        line = p.stdout.readline()
        if not line:
            raise SystemExit("server closed stdout")
        if not line.startswith("{"):      # JFR/JVM chatter on stdout
            continue
        r = json.loads(line)
        if r.get("id") == n[0]:
            return r, time.time() - t0


def call(tool, args):
    r, dt = rpc("tools/call", {"name": tool, "arguments": args})
    txt = r.get("result", {}).get("content", [{}])[0].get("text", "")
    return dt, len(txt)


_, dt = rpc("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                           "clientInfo": {"name": "mcp-roundtrip", "version": "0"}})
print(f"initialize      {dt:6.2f}s")
rpc("notifications/initialized", notify=True)
r, dt = rpc("tools/list")
print(f"tools/list      {dt:6.2f}s ({len(r['result']['tools'])} tools)")
for _ in range(a.rounds):
    for tool, args in plan:
        dt, chars = call(tool, args)
        print(f"{dt:6.2f}s {tool}/{args.get('op', ''):20s} {chars:7d} chars")
p.stdin.close()
p.wait(timeout=120)
print("server exit", p.returncode)
