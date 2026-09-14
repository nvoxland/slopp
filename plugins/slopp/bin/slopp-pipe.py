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
duplicates the plugin's entry.

The pipe FINDS a daemon; it never starts one. The daemon is a process the
user runs and owns (`slopp daemon`, in a terminal or a service), and when
none answers on the configured port this entry fails with a sentence
saying so — a server nobody started is a server nobody knows to stop,
look at, or upgrade. Mid-session, a daemon that went away (a `slopp daemon
stop`, a restart onto a new jar) is waited for briefly, so a client
survives the restart it was asked to make.

    SLOPP_DAEMON_URL   override the daemon's address (else the daemon file)
    SLOPP_DAEMON_PORT  which daemon: the machine's (default; its port is
                       daemon-port in ~/.slopp/config.json, else 7357) or a
                       DEV instance on another port, one a project's dev
                       config runs. Neither is ever started from here.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.expanduser("~/.slopp/config.json")


def machine_port():
    """The port THE daemon of this machine listens on: daemon-port in
    ~/.slopp/config.json, else 7357. The daemon reads the same file."""
    try:
        return int(json.load(open(CONFIG_FILE)).get("daemon-port") or 7357)
    except Exception:
        return 7357


def wanted_port():
    return int(os.environ.get("SLOPP_DAEMON_PORT") or machine_port())


def daemon_file():
    """~/.slopp/daemon.json is the machine daemon's; any other port is a dev
    instance and records itself under daemon-<port>.json."""
    port = wanted_port()
    name = "daemon.json" if port == machine_port() else f"daemon-{port}.json"
    return os.path.expanduser("~/.slopp/" + name)
# how long a mid-session call waits for a daemon the user is bringing back
RECONNECT_WAIT = float(os.environ.get("SLOPP_PIPE_RECONNECT_WAIT") or 60)
PROJECT = os.path.realpath(os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd())
SLUG = os.path.basename(PROJECT.rstrip("/")) or "root"


def log(msg):
    print("slopp-pipe: " + msg, file=sys.stderr, flush=True)


def live_url():
    """The daemon's base url when one is live, else None."""
    if os.environ.get("SLOPP_DAEMON_URL"):
        return os.environ["SLOPP_DAEMON_URL"].rstrip("/")
    try:
        info = json.load(open(daemon_file()))
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


def require_daemon(wait=0):
    """A live daemon's url. None when nothing answers on the configured
    port within `wait` seconds — and that is the caller's to report; nothing
    here starts a daemon."""
    deadline = time.time() + wait
    while True:
        url = live_url()
        if url and status_ok(url):
            return url
        if time.time() >= deadline:
            return None
        time.sleep(0.5)


def error_reply(line, message):
    """A JSON-RPC error for the request on `line`, so the client learns why
    rather than waiting on a call that will never answer; empty for a
    notification, which takes no reply."""
    try:
        rid = json.loads(line).get("id")
    except Exception:
        rid = None
    if rid is None:
        return ""
    return json.dumps({"jsonrpc": "2.0", "id": rid,
                       "error": {"code": -32000, "message": "slopp: " + message}})


def no_daemon_sentence():
    if os.environ.get("SLOPP_DAEMON_URL"):
        return (f"no slopp daemon answers at {os.environ['SLOPP_DAEMON_URL']} (SLOPP_DAEMON_URL)"
                f" — start one with `slopp daemon`, then reconnect")
    port = wanted_port()
    if port != machine_port():
        return (f"no daemon answers on port {port}, a dev instance — its project's"
                f" machine daemon starts it on attach and re-serves it at every done")
    return (f"no slopp daemon answers on port {port} — start one with `slopp daemon`"
            f" (it stays up until `slopp daemon stop`), then reconnect")


def main():
    base = require_daemon()
    if not base:
        log(no_daemon_sentence())
        sys.exit(1)
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
                    # new session on the client's behalf and send again. The
                    # daemon that came back may answer at a DIFFERENT base
                    # (measured 2026-09-08: the root moved from /slopp/ to
                    # /api/ under a live pipe, and replaying initialize at
                    # the old endpoint was a 404 forever), so the base is
                    # re-read from its file first, not assumed.
                    base = require_daemon(RECONNECT_WAIT) or base
                    endpoint = f"{base}/projects/{SLUG}/mcp"
                    session = reinitialize()
                    if session:
                        req = post(line, session)
                        continue
                break
            except Exception as e:
                # the daemon went away under us (a `slopp daemon stop`, a
                # restart onto a new jar). Wait for the one the user brings
                # back and try again: the answer is late rather than the
                # session dead. The session id went with the old daemon, so
                # the pipe re-initializes before re-sending.
                if attempt < 3:
                    log(f"daemon unreachable ({e}) — waiting for one to answer")
                    found = require_daemon(RECONNECT_WAIT)
                    if found:
                        base = found
                        endpoint = f"{base}/projects/{SLUG}/mcp"
                        session = reinitialize()
                        req = post(line, session)
                        continue
                log(f"daemon unreachable: {e}")
                body = error_reply(line, no_daemon_sentence())
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
