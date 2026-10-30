#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

dir="$(mktemp -d)"
cat >"$dir/cucumber.json" <<'JSON'
[{"uri":"features/login.feature","name":"Login","elements":[
  {"name":"valid login","steps":[{"result":{"status":"passed"}}]},
  {"name":"bad password","steps":[{"result":{"status":"failed"}}]},
  {"name":"skipped one","steps":[{"result":{"status":"skipped"}}]}
]}]
JSON
printf '{"pattern": "**/cucumber.json", "skipMarkingUnstable": "true"}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(output "$dir" scenarios)" = "3" ] || fail "scenarios: $(cat "$dir/output.json")"
[ "$(output "$dir" failed)" = "1" ] || fail "failed"
[ "$(output "$dir" passed)" = "1" ] || fail "passed"

dir="$(mktemp -d)"; printf 'not json' >"$dir/x.json"
printf '{"pattern": "*.json"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a non-cucumber json must fail"

echo "smoke ok: scenario counts, skipMarkingUnstable, non-cucumber json"
