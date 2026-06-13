#!/usr/bin/env bash
#
# Event Ledger — end-to-end regression
# Maps each check to a section of the take-home handout.
#
# Usage:
#   chmod +x e2e-test.sh
#   ./e2e-test.sh
#
# Override endpoints if needed:
#   GW=http://localhost:8080 ACCT=http://localhost:8081 ./e2e-test.sh
#
# Requires: curl, jq, openssl (openssl only for the tracing phase)

set -uo pipefail

GW=${GW:-http://localhost:8080}
ACCT=${ACCT:-http://localhost:8081}

RUN=$(date +%s)
ACC="acct-e2e-$RUN"          # unique account per run -> re-runnable without restarting services
EVT1="evt-$RUN-001"
EVT2="evt-$RUN-002"
EVT0="evt-$RUN-000"          # out-of-order (earliest timestamp, arrives last)

PASS=0
FAIL=0
BODYFILE=$(mktemp)
trap 'rm -f "$BODYFILE"' EXIT

c_green() { printf '\033[32m%s\033[0m\n' "$1"; }
c_red()   { printf '\033[31m%s\033[0m\n' "$1"; }
c_bold()  { printf '\n\033[1m%s\033[0m\n' "$1"; }

ok()  { PASS=$((PASS+1)); c_green "  PASS  $1"; }
bad() { FAIL=$((FAIL+1)); c_red   "  FAIL  $1"; }

command -v jq >/dev/null   || { c_red "jq is required (brew install jq / apt install jq)"; exit 1; }

# call METHOD URL [JSON]  -> sets $STATUS and writes body to $BODYFILE
call() {
  local method=$1 url=$2 data=${3:-}
  if [ -n "$data" ]; then
    STATUS=$(curl -s -o "$BODYFILE" -w '%{http_code}' -X "$method" "$url" \
             -H 'Content-Type: application/json' -d "$data")
  else
    STATUS=$(curl -s -o "$BODYFILE" -w '%{http_code}' -X "$method" "$url")
  fi
}

expect_status() {  # want label
  [ "$STATUS" = "$1" ] && ok "$2 ($STATUS)" || bad "$2 (got $STATUS, want $1)"
}

balance() { curl -s "$ACCT/accounts/$ACC/balance" | jq -r '.balance // empty'; }

expect_balance() {  # want
  local want=$1 got
  got=$(balance)
  if [ -z "$got" ]; then bad "balance: no .balance field in response"; return; fi
  awk -v g="$got" -v w="$want" 'BEGIN{d=g-w; if(d<0)d=-d; exit !(d<0.001)}' \
    && ok "balance == $want (got $got)" \
    || bad "balance: got $got, want $want"
}

evt() {  # eventId type amount ts
  printf '{"eventId":"%s","accountId":"%s","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"%s"}' \
    "$1" "$ACC" "$2" "$3" "$4"
}

# ----------------------------------------------------------------------------
c_bold "Account under test: $ACC"

# === Service Separation (#2) + Health (#4) ==================================
c_bold "Phase 1 — health & service separation"
call GET "$GW/health";   expect_status 200 "Gateway   GET /health"
call GET "$ACCT/health"; expect_status 200 "Account   GET /health"
if [ "$STATUS" != "200" ]; then
  c_red "Both services must be up. Start them and re-run."; exit 1
fi
echo "  note: two independent processes, each its own /health -> separate runtimes confirmed"

# === Core: balance, happy path (#1) ========================================
c_bold "Phase 2 — happy path (create + apply + read)"
call POST "$GW/events" "$(evt "$EVT1" CREDIT 200.00 2026-05-15T10:00:00Z)"
expect_status 201 "POST $EVT1 (CREDIT 200) -> created"
call POST "$GW/events" "$(evt "$EVT2" DEBIT 75.00 2026-05-15T10:30:00Z)"
expect_status 201 "POST $EVT2 (DEBIT 75)  -> created"
call GET "$GW/events/$EVT1"; expect_status 200 "GET /events/$EVT1"
expect_balance 125.00         # 200 - 75

# === Core: idempotency (#1) ================================================
c_bold "Phase 3 — idempotency (re-submit same eventId)"
call POST "$GW/events" "$(evt "$EVT1" CREDIT 200.00 2026-05-15T10:00:00Z)"
expect_status 200 "re-POST $EVT1 -> existing returned (NOT 201)"
expect_balance 125.00         # unchanged — the whole point

# === Core: out-of-order tolerance (#1) =====================================
c_bold "Phase 4 — out-of-order tolerance"
call POST "$GW/events" "$(evt "$EVT0" CREDIT 50.00 2026-05-15T09:00:00Z)"
expect_status 201 "POST $EVT0 (earlier timestamp, arrives last)"
expect_balance 175.00         # 200 - 75 + 50 — order-independent aggregate
RESP=$(curl -s "$GW/events?account=$ACC")
echo "$RESP" | jq -e '(if type=="array" then . else .events end)
                       | ([.[].eventTimestamp]) == ([.[].eventTimestamp] | sort)' >/dev/null 2>&1 \
  && ok "listing sorted ascending by eventTimestamp" \
  || bad "listing not sorted (or response shape differs — check the jq path)"

# === Core: validation (#1) =================================================
c_bold "Phase 5 — validation (all must be 400, never 500)"
call POST "$GW/events" "$(evt "evt-$RUN-bad1" TRANSFER 10 2026-05-15T10:00:00Z)"
expect_status 400 "unknown type -> 400"
call POST "$GW/events" "$(evt "evt-$RUN-bad2" CREDIT 0 2026-05-15T10:00:00Z)"
expect_status 400 "zero amount -> 400"
call POST "$GW/events" "$(evt "evt-$RUN-bad3" DEBIT -5 2026-05-15T10:00:00Z)"
expect_status 400 "negative amount -> 400"
call POST "$GW/events" '{"eventId":"evt-'"$RUN"'-bad4","accountId":"'"$ACC"'","type":"CREDIT","currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}'
expect_status 400 "missing amount -> 400"
call POST "$GW/events" '{"eventId":"evt-'"$RUN"'-bad5","type":"CREDIT","amount":10,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}'
expect_status 400 "missing accountId -> 400"

# === Distributed tracing (#3) — guided ====================================
c_bold "Phase 6 — trace propagation (manual verification)"
if command -v openssl >/dev/null; then
  TP="00-$(openssl rand -hex 16)-$(openssl rand -hex 8)-01"
  TID=$(echo "$TP" | cut -d- -f2)
  curl -s -o /dev/null -X POST "$GW/events" \
    -H 'Content-Type: application/json' -H "traceparent: $TP" \
    -d "$(evt "evt-$RUN-trace" CREDIT 1.00 2026-05-15T13:00:00Z)"
  echo "  sent POST with traceparent carrying trace id: $TID"
  echo "  -> grep BOTH service logs for that id; the same value must appear in each:"
  echo "       grep $TID <gateway-log>"
  echo "       grep $TID <account-log>"
  echo "  (a single client request producing one traceable path across both services)"
else
  echo "  openssl not found — skipped. Verify manually that a Gateway request's trace id"
  echo "  also appears in the Account Service's structured logs."
fi

# === Observability: custom metric (#4) — guided ===========================
c_bold "Phase 7 — custom metric (manual verification)"
echo "  if exposed via Actuator/Micrometer, confirm your metric is present, e.g.:"
echo "     curl -s $GW/actuator/metrics | jq"
echo "     curl -s $GW/actuator/metrics/events.processed | jq   # adjust to your metric name"

# === Resiliency (#5) + Graceful degradation (#6) ===========================
c_bold "Phase 8 — graceful degradation (interactive)"
read -rp "  >> STOP the Account Service (Ctrl-C on :8081), then press Enter... " _
echo "  reads depend only on the Gateway's local data — must still succeed:"
call GET "$GW/events/$EVT1";          expect_status 200 "GET /events/$EVT1  (degraded read)"
call GET "$GW/events?account=$ACC";   expect_status 200 "GET /events?account (degraded read)"
echo "  writes depend on the Account Service — must fail CLEAN (503), not 500/hang:"
DEG="evt-$RUN-degraded"
call POST "$GW/events" "$(evt "$DEG" CREDIT 10.00 2026-05-15T14:00:00Z)"
expect_status 503 "POST during outage -> 503"
echo "  balance query (Account Service unreachable) should return a clear error, not hang."
echo "  -> if your Gateway proxies balance, check it now; a direct call to :8081 will refuse."

read -rp "  >> RESTART the Account Service (:8081), then press Enter... " _
echo "  apply-first means the failed write left NO orphan event in the Gateway store:"
call GET "$GW/events/$DEG"; expect_status 404 "failed event absent after recovery -> 404"

# ----------------------------------------------------------------------------
c_bold "Summary"
echo "  passed: $PASS    failed: $FAIL"
[ "$FAIL" -eq 0 ] && c_green "ALL AUTOMATED CHECKS PASSED" || c_red "SOME CHECKS FAILED"
exit $((FAIL > 0))