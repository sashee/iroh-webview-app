#!/usr/bin/env bash
# The demo behind two iroh endpoints: the setup README.md's passkey checks need.
# Run it inside the demo's nix-shell, which provides iroh-uds-listen:
#
#   nix-shell passkey-demo --run 'passkey-demo/over-iroh.sh ~/.local/state/passkey-demo'
#
# Two endpoints in front of one demo are two sites to the app: each endpoint id
# becomes its own <label>.localhost origin, with its own cookies and passkeys.
# Their secret keys are kept in the state directory, because the app's origin
# is a function of the endpoint id -- a fresh key on every start would orphan
# every passkey made against the last one. Delete the directory to start over.
set -euo pipefail

state=${1:?usage: over-iroh.sh <state-dir>}
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$state"
chmod 700 "$state"

for name in alpha beta; do
  if [ ! -s "$state/$name.secret" ]; then
    (umask 077 && iroh-ssh-generate-secret 2>/dev/null >"$state/$name.secret")
  fi
done

trap 'kill 0' EXIT

python "$here/passkey_demo.py" --unix "$state/demo.sock" --state "$state/passkeys.json" &

# The listeners print tickets carrying their relay urls once they are online;
# those are the ones to paste, since they spare the phone a DNS lookup.
for name in alpha beta; do
  IROH_SECRET=$(cat "$state/$name.secret") iroh-uds-listen "$state/demo.sock" 2>&1 |
    sed -u "s/^/[$name] /" &
done

wait
