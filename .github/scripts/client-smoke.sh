#!/usr/bin/env bash
# Client smoke test for the macOS and Windows jobs (Git Bash on Windows): the native binary serves as a localhost
# test server, then `tun login` and `tun http` run against it and the saved config.toml must be private.
set -euo pipefail
tun=$1
sha256() { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; }

dir=$(mktemp -d)
tok=$(openssl rand -hex 32)
printf 'version = 1\n[server]\ndomain = "localhost"\nport = 18080\n[[token]]\nalias = "ci"\nhash = "sha256:%s"\nlevel = "random"\n' \
  "$(printf %s "$tok" | sha256 | cut -d' ' -f1)" > "$dir/tun.toml"
"$tun" server --config-dir "$dir" & pid=$!
cpid=
trap 'kill $pid $cpid 2>/dev/null || true' EXIT
for _ in $(seq 100); do curl -fsS -H 'Host: localhost' 127.0.0.1:18080/_tun/health >/dev/null 2>&1 && break; sleep 0.1; done

home=$(mktemp -d)
case "$(uname -s)" in
  Darwin)
    export HOME=$home
    config="$home/Library/Application Support/tun/config.toml"
    ;;
  MINGW* | MSYS*)
    export APPDATA=$(cygpath -w "$home")
    config="$home/tun/config.toml"
    ;;
  *) echo "unexpected OS $(uname -s)"; exit 1 ;;
esac

"$tun" login ws://localhost:18080 "$tok"
"$tun" http 8080 > "$dir/out" 2> "$dir/err" & cpid=$!
for _ in $(seq 100); do grep -qE '^https://[a-z2-7]{8,}\.localhost$' "$dir/out" && break; sleep 0.1; done
grep -qE '^https://[a-z2-7]{8,}\.localhost$' "$dir/out" || { echo "tun http said '$(cat "$dir/out")' / '$(cat "$dir/err")'"; exit 1; }

[ -f "$config" ] || { echo "no $config; $home holds: $(find "$home" -type f)"; exit 1; }
case "$(uname -s)" in
  Darwin)
    mode=$(stat -f %Lp "$config")
    [ "$mode" = "600" ] || { echo "config.toml is mode $mode, expected 600"; exit 1; }
    ;;
  *)
    acl=$(icacls "$(cygpath -w "$config")" | tr -d '\r')
    entries=$(echo "$acl" | grep -o ' [^ ]*:(.*' || true)
    echo "icacls: $acl"
    [ "$(echo "$entries" | grep -c .)" = "1" ] || { echo "expected one ACL entry, got: $entries"; exit 1; }
    echo "$entries" | grep -qi "\\\\$USERNAME:(" || { echo "the ACL entry is not for $USERNAME: $entries"; exit 1; }
    if echo "$entries" | grep -q '(I)'; then echo "inherited ACL entry: $entries"; exit 1; fi
    ;;
esac
echo "client smoke test passed: $config"
