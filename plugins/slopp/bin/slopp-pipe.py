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
    initialize = None   # the client's own initialize, replayed on the client's behalf
    out = sys.stdout

    def post(payload, sid):
        h = {"Content-Type": "application/json",
             "Accept": "application/json, text/event-stream",
             "X-Slopp-Dir": PROJECT}
        if sid:
            h["Mcp-Session-Id"] = sid
        return urllib.request.Request(endpoint, data=payload.encode("utf-8"),
                                      headers=h, method="POST")

    def reinitialize():
        """A stdio client never re-initializes on its own, so when the daemon
        has forgotten this session (a restart, an idle reap) the pipe replays
        the client's initialize and re-sends the notification, and the client
        sees only a late answer. Returns the new session id, or None."""
        if not initialize:
            return None
        try:
            with urllib.request.urlopen(post(initialize, None), timeout=120) as r:
                sid = r.headers.get("Mcp-Session-Id")
                r.read()
            urllib.request.urlopen(
                post(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}), sid),
                timeout=30).read()
            log(f"session re-initialized at the daemon")
            return sid
        except Exception as e:
            log(f"could not re-initialize: {e}")
            return None

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            if json.loads(line).get("method") == "initialize":
                initialize = line
        except Exception:
            pass
        req = post(line, session)
        body = None
        for attempt in (1, 2, 3):
            try:
                with urllib.request.urlopen(req, timeout=3600) as r:
                    sid = r.headers.get("Mcp-Session-Id")
                    if sid:
                        session = sid
                    body = r.read().decode("utf-8")
                break
            except urllib.error.HTTPError as e:
                body = e.read().decode("utf-8")
                if e.code == 404 and session and attempt < 3:
                    # the daemon forgot us (a restart, an idle reap): mint a
                    # new session on the client's behalf and send again
                    session = reinitialize()
                    if session:
                        req = post(line, session)
                        continue
                break
            except Exception as e:
                # the daemon went away under us (a `slopp daemon stop`, a
                # kernel restart). Bring one up and try ONCE more: the answer
                # is late rather than the session dead. The session id is
                # gone with the old daemon; a 404 on the retry tells the
                # client to re-initialize.
                if attempt < 3:
                    log(f"daemon unreachable ({e}) — ensuring one and retrying")
                    base = ensure_daemon()
                    endpoint = f"{base}/projects/{SLUG}/mcp"
                    session = reinitialize()
                    req = post(line, session)
                    continue
                log(f"daemon unreachable: {e}")
                body = None
        if body is None and not line:
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
