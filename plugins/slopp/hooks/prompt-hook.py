#!/usr/bin/env python3
"""UserPromptSubmit hook: capture the verbatim ask for slopp's turn
machinery, and inject the ASK BUNDLE so the model starts with its map —
moonshot A: the session_brief/orient/first-read turns happen before the
first token. Three tiers, every failure silent (the hook must never block
a prompt):
  1. GET /api/bundle from the RUNNING listener (.slopp/ui-port) — the real
     orient walk with the seeds' sources. The server may still be BOOTING
     (the first prompt of every session), so this tier WAITS, polling up
     to ~6.5 s: seconds of wall for the map that saves the whole
     orientation wave of model requests.
  2. a sqlite-native mini-bundle when no listener came up: the ask's words
     — and their hyphenated bigrams, so "billable weight" finds
     billable-weight-g — matched against form names, those sources inline;
  3. the old micro-brief.
Recent asks + the last milestone + a red-done heads-up ride along from
sqlite whichever tier answered. stdout becomes prompt context."""
import json
import os
import re
import sqlite3
import sys
import time
import urllib.request
import urllib.parse

MAX_CHARS = 9000
HTTP_BUDGET_S = 6.5

prompt = ""
SID = ""
try:
    d = json.load(sys.stdin)
    prompt = d.get("prompt", "") or ""
    SID = d.get("session_id", "") or ""
    if os.path.exists(".slopp/store.db"):
        sid = d.get("session_id", "")
        payload = {"session-id": sid, "prompt": prompt}
        # Two mailboxes: the unscoped legacy slot and this session's own, so
        # a second session's ask can never overwrite an unread first.
        with open(".slopp/pending-intent", "w") as f:
            json.dump(payload, f)
        safe = re.sub(r"[^A-Za-z0-9_-]", "", sid)
        if safe:
            with open(".slopp/pending-intent." + safe, "w") as f:
                json.dump(payload, f)
except Exception:
    pass


def http_bundle_once():
    """One attempt at the running listener's /api/bundle — None on any miss."""
    try:
        with open(".slopp/ui-port") as f:
            info = json.load(f)
        pid = int(info.get("pid", 0))
        if pid:
            os.kill(pid, 0)  # raises if that process is gone
        url = (info["url"].rstrip("/") + "/api/bundle?ask="
               + urllib.parse.quote(prompt[:500])
               # the server answers a session it has already mapped with the
               # small DELTA instead of a second full map (bundle diet 3c)
               + ("&session-id=" + urllib.parse.quote(SID) if SID else "")
               # a CLI cell's bundle speaks the CLI loop (SLOPP_CLI cells
               # advertise no MCP tools, so the MCP voice points at nothing)
               + ("&cli=1" if os.environ.get("SLOPP_CLI") else ""))
        with urllib.request.urlopen(url, timeout=2.0) as r:
            body = json.loads(r.read().decode("utf-8"))
        b = body.get("bundle")
        return b if b and b.strip() else None
    except Exception:
        return None


def http_bundle():
    """Tier 1, with patience: the server boots BESIDE this prompt on a
    session's first ask, so poll — a few seconds of wall here buys the map
    that otherwise costs the model its whole orientation wave."""
    deadline = time.time() + HTTP_BUDGET_S
    while True:
        b = http_bundle_once()
        if b is not None:
            return b
        if time.time() >= deadline:
            return None
        time.sleep(0.3)


def sqlite_bundle(c):
    """Tier 2: the ask's words matched against form names, sources inline.
    Exact tokens first; then hyphenated bigrams and long-token prefixes, so
    prose ("billable weight", "the oversize rule") finds the forms it means."""
    try:
        toks = re.findall(r"[A-Za-z][A-Za-z0-9_?!*<>=+.-]{2,}", prompt)
        toks = list(dict.fromkeys(toks))[:24]
        if not toks:
            return None
        lower = [t.lower() for t in toks]
        bigrams = [a + "-" + b for a, b in zip(lower, lower[1:])][:12]
        rows = []
        seen = set()

        def add(rs):
            for ns, name, src in rs:
                k = (ns, name)
                if k not in seen and src:
                    seen.add(k)
                    rows.append((ns, name, src))

        marks = ",".join("?" for _ in toks)
        add(c.execute(
            "SELECT DISTINCT ns, name, source FROM elements "
            "WHERE source IS NOT NULL AND name IS NOT NULL AND ns != name "
            "AND (name IN (%s) OR ns IN (%s)) LIMIT 6" % (marks, marks),
            toks + toks).fetchall())
        for pat in bigrams + [t for t in lower if len(t) >= 6]:
            if len(rows) >= 6:
                break
            add(c.execute(
                "SELECT DISTINCT ns, name, source FROM elements "
                "WHERE source IS NOT NULL AND name IS NOT NULL AND ns != name "
                "AND lower(name) LIKE ? LIMIT 2", (pat + "%",)).fetchall())
        if not rows:
            return None
        out, used = [], 0
        for ns, name, src in rows[:6]:
            if used + len(src) > 6500:
                continue
            out.append(";; %s/%s\n%s" % (ns, name, src))
            used += len(src)
        if not out:
            return None
        return ("the forms this ask names, in full (current — no need to"
                " re-read them):\n" + "\n\n".join(out))
    except Exception:
        return None


def tail_context(c):
    """Recent asks, last milestone, red-done heads-up — from sqlite."""
    bits = []
    try:
        n = c.execute("SELECT COUNT(DISTINCT ns) FROM elements").fetchone()[0]
        row = c.execute("SELECT payload FROM deltas WHERE op='commit' "
                        "ORDER BY seq DESC LIMIT 1").fetchone()
        m = re.search(r':description "((?:[^"\\]|\\.)*)"', row[0]) if row else None
        desc = (m.group(1) if m else "none yet")[:80]
        bits.append("[slopp] live store here: %d namespaces; last milestone: %s."
                    " Work through the slopp tools — the store is the source,"
                    " not the files." % (n, desc))
        asks = c.execute("SELECT payload FROM deltas WHERE op='turn-begin' "
                         "ORDER BY seq DESC LIMIT 8").fetchall()
        seen = []
        for (p,) in asks:
            im = re.search(r':intent "((?:[^"\\]|\\.)*)"', p)
            if im:
                t = im.group(1)[:110]
                # system continuations (task notifications) also fire this
                # hook; they are not asks and drown the real ones
                if t and t not in seen and "task-notification" not in t \
                        and not t.startswith("<") and not t.startswith("\\u003c"):
                    seen.append(t)
        if seen:
            bits.append("recent asks here: " + " | ".join(seen[:5]))
        # the standing whole-store verdict, when one stands: the s8 census
        # showed a closing full_check + test_run at nearly every step's end —
        # a verdict already in hand is the one thing that removes those turns
        fc = c.execute("SELECT seq, payload FROM deltas WHERE op='verify' "
                       "AND payload LIKE '%:full-check%' "
                       "ORDER BY seq DESC LIMIT 1").fetchone()
        if fc:
            moved = c.execute(
                "SELECT COUNT(*) FROM deltas WHERE seq > ? AND op NOT IN "
                "('verify','done','commit','turn-begin','turn-end',"
                "'observe','read-cost')", (fc[0],)).fetchone()[0]
            if moved == 0:
                color = ("GREEN" if ":status :green" in fc[1]
                         else "RED" if ":status :red" in fc[1] else "recorded")
                bits.append("standing verdict: the whole-store full_check is "
                            + color + " and STANDS — nothing has changed since."
                            " done re-verifies your episode itself; no closing"
                            " full_check or test_run is needed.")
        done_row = c.execute("SELECT payload FROM deltas WHERE op='done' "
                             "ORDER BY seq DESC LIMIT 1").fetchone()
        if done_row and ":findings" in done_row[0]:
            fm = re.search(r":failures (\d+)", done_row[0])
            fails = int(fm.group(1)) if fm else 0
            if fails:
                bits.append("HEADS-UP: the last done-point left %d failing"
                            " test(s) — session_brief :last-done has details."
                            % fails)
    except Exception:
        pass
    return bits


SYSTEM_TURN = prompt.lstrip().startswith("<") or "task-notification" in prompt

try:
    if os.path.exists(".slopp/store.db"):
        # a system continuation (task notification, hook wake-up) is not an
        # ask: the model is mid-task and the map would be noise on its meter
        body = None if SYSTEM_TURN else http_bundle()
        c = sqlite3.connect("file:.slopp/store.db?mode=ro", uri=True)
        if body is None and not SYSTEM_TURN:
            body = sqlite_bundle(c)
        bits = tail_context(c)
        c.close()
        parts = ([] if body and body.startswith("[slopp]") else bits[:1]) \
            + ([body] if body else []) + bits[1:]
        text = "\n".join(p for p in parts if p)
        if text:
            print(text[:MAX_CHARS])
except Exception:
    pass
