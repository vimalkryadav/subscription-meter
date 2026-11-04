#!/usr/bin/env bash
# Installed only in a dedicated deploy checkout; never resets the user's working tree.
set -euo pipefail
release_sha=${1:?Full SHA required}
release_image=${2:?Image digest required}
[[ "$release_sha" =~ ^[0-9a-f]{40}$ ]]
[[ "$release_image" =~ ^ghcr.io/vimalkryadav/control-usage@sha256:[0-9a-f]{64}$ ]]
cd /home/coder/control-usage-deploy
[[ -f deploy/state/bootstrap.json ]] || { echo 'Bootstrap is not configured.' >&2; exit 1; }
exec 9>deploy/state/receive.lock
flock 9
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || { echo 'Deployment checkout has local modifications; refusing overwrite.' >&2; exit 1; }
git fetch origin main
# Only commits already present on trusted main can execute migrations/deployment code.
git merge-base --is-ancestor "$release_sha" origin/main
git checkout --detach "$release_sha"
export CONTROL_USAGE_RUNTIME_ROOT=/home/coder/subscription-meter
export CONTROL_USAGE_DOCKER_SUDO=1
exec python3 scripts/blue_green.py --sha "$release_sha" --image "$release_image"
