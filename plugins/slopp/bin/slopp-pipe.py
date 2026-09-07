#!/usr/bin/env python3
"""The stdio PIPE onto the daemon: what Claude Code's plugin entry runs
when SLOPP_DAEMON=1. It is a transport adapter and nothing more — each
JSON-RPC line on stdin is POSTed to the daemon's per-project MCP endpoint,
the answer line goes to stdout, and the Mcp-Session-Id the daemon minted
on `initialize` rides every later request. The daemon speaks MCP itself;
this process implements none of it.

Why a pipe rather than an HTTP entry in the plugin's .mcp.json: a stdio
server is launched IN THE PROJECT DIR, so the one fact the daemon needs
(which project) is this process's cwd, for free. A plugin-level HTTP entry
cannot name it — its headersHelper runs in the plugin root and receives no
project variable — and a per-project .mcp.json needs an approval prompt and
duplicates the plugin's entry. A pipe also outlives Claude Code's ~7 s
HTTP startup window: it starts a dead daemon and waits for it to bind.

    SLOPP_DAEMON_URL   override the daemon's address (else ~/.slopp/daemon.json)
    SLOPP_DAEMON_PORT  the port a daemon started here listens on (default 7357)
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DAEMON_FILE = os.path.expanduser("~/.slopp/daemon.json")
PROJECT = os.path.realpath(os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd())
SLUG = os.path.basename(PROJECT.rstrip("/")) or "root"


def log(msg):
    print("slopp-pipe: " + msg, file=sys.stderr, flush=True)


def live_url():
    """The daemon's base url when one is live, else None."""
    if os.environ.get("SLOPP_DAEMON_URL"):
        return os.environ["SLOPP_DAEMON_URL"].rstrip("/")
    try:
        info = json.load(open(DAEMON_FILE))
        os.kill(int(info["pid"]), 0)
        return info["url"].rstrip("/")
    except Exception:
        return None


def status_ok(url):
    try:
        with urllib.request.urlopen(url + "/status", timeout=2) as r:
            return r.status == 200
    except Exception:
        return False


def ensure_daemon():
    """A live daemon's url — starting one, detached, when none answers."""
    url = live_url()
    if url and status_ok(url):
        return url
    port = os.environ.get("SLOPP_DAEMON_PORT", "7357")
    log(f"no daemon answering — starting one on port {port}")
    env = {k: v for k, v in os.environ.items() if k != "CLAUDE_PROJECT_DIR"}
    subprocess.Popen([os.path.join(HERE, "slopp"), "daemon", port],
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, env=env, start_new_session=True)
    deadline = time.time() + 90
    while time.time() < deadline:
        url = live_url()
        if url and status_ok(url):
            return url
        time.sleep(0.3)
    log("the daemon did not come up in 90s")
    sys.exit(1)


def main():
    base = ensure_daemon()
    endpoint = f"{base}/projects/{SLUG}/mcp"
    session = None
    out = sys.stdout
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        headers = {"Content-Type": "application/json",
                   "Accept": "application/json, text/event-stream",
                   "X-Slopp-Dir": PROJECT}
        if session:
            headers["Mcp-Session-Id"] = session
        req = urllib.request.Request(endpoint, data=line.encode("utf-8"),
                                     headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=3600) as r:
                sid = r.headers.get("Mcp-Session-Id")
                if sid:
                    session = sid
                body = r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8")
            if e.code == 404 and session:
                # the daemon reaped us (idle) or restarted: the client's next
                # initialize mints a new session; say so once
                log("session gone at the daemon — reconnect (/mcp) to re-initialize")
                session = None
        except Exception as e:
            log(f"daemon unreachable: {e}")
            break
        if body.strip():
            out.write(body.strip() + "\n")
            out.flush()
    if session:
        try:
            req = urllib.request.Request(endpoint, headers={"Mcp-Session-Id": session,
                                                            "X-Slopp-Dir": PROJECT},
                                         method="DELETE")
            urllib.request.urlopen(req, timeout=10).read()
        except Exception:
            pass


if __name__ == "__main__":
    main()
