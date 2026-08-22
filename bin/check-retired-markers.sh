#!/usr/bin/env bash
# Do the SHIPPED docs still teach a marker slopp no longer reads?
#
# The half of a rename that historically fails. On 2026-08-02 a rename rewrote
# the store and walked past the docs, and four defects shipped — including a
# skill telling agents to read a session_brief key that returned nil. An author
# who copies a retired marker out of a skill writes a declaration that refuses
# nothing, generates nothing and changes nothing: no error, no warning, and no
# symptom until something downstream is quietly absent.
#
# It lives HERE rather than in the store because the store cannot see these
# files. Both test tiers run in a materialized temp dir, so `plugins/slopp/...`
# resolves to nothing there — an in-store version read ZERO files and passed
# every absence assertion vacuously, which is the exact failure this is for.
#
# The retired list is DERIVED from slopp.index.crossings/retired-markers, so
# adding a rename to the ledger extends this check by itself. A hand-kept
# second list is what the ledger exists to avoid.
#
# Scope is what SHIPS. `.context/` and `ideas/` are history and incident
# records, where the retired spelling is the correct thing to write.
set -euo pipefail
cd "$(dirname "$0")/.."

ROOTS=(plugins/slopp/skills docs)
SLOPP="${SLOPP:-plugins/slopp/bin/slopp}"

# the ledger's retired side is stored WITHOUT its leading colon, deliberately:
# written as a keyword it is an occurrence of the very name being renamed, and
# the sweep ate it once already. Put the colon back here.
MARKERS=$("$SLOPP" --call query_store \
  '{"code":"(fn [store] (vec (sort (keys slopp.index.crossings/retired-markers))))"}' \
  2>/dev/null | grep -o '"[a-z]*/[a-z-]*"' | tr -d '"' | sed 's/^/:/' | sort -u || true)

if [ -z "$MARKERS" ]; then
  echo "check-retired-markers: FAILED to read the ledger — cannot tell a clean"
  echo "  sweep from a scan that read nothing. Is the store reachable?"
  exit 2
fi

# population control: an empty file set satisfies every absence check below
FILES=$(find "${ROOTS[@]}" -name '*.md' -type f 2>/dev/null | wc -l | tr -d ' ')
if [ "$FILES" -lt 5 ]; then
  echo "check-retired-markers: only $FILES shipped docs found — the roots moved"
  exit 2
fi

bad=0
for m in $MARKERS; do
  # word-boundary on the right so :http/read does not match :http/reads
  if hits=$(grep -rn --include='*.md' -E "${m}([^a-z-]|$)" "${ROOTS[@]}" 2>/dev/null); then
    echo "RETIRED MARKER still taught in a shipped doc: $m"
    echo "$hits" | sed 's/^/    /' | head -5
    bad=1
  fi
done

if [ "$bad" -ne 0 ]; then
  echo
  echo "A shipped doc naming a marker nothing reads is worse than a missing one:"
  echo "an author copies it and gets a declaration with no behaviour and no symptom."
  exit 1
fi

echo "check-retired-markers: clean over $FILES shipped docs, $(echo "$MARKERS" | wc -w | tr -d ' ') retired markers"
