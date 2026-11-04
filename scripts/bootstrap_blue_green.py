#!/usr/bin/env python3
"""First compatibility handoff, with an explicit admission pause and no edge/tunnel restart."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import time
import urllib.request
import blue_green as deploy


def maintenance_config(config):
    pause='''    @bootstrapPause path /gateway/* /portal/api/* /admin/*
    handle @bootstrapPause {
        header Retry-After "60"
        respond "Initial deployment handoff in progress. Retry shortly." 503
    }
'''
    if config.count(":8182 {")!=1:
        raise ValueError("Unrecognized edge listener; manual review required")
    return config.replace(":8182 {", ":8182 {\n"+pause,1)


def pending_legacy(env):
    sql="SELECT COUNT(*) FROM requests WHERE owner_instance IS NULL AND state='pending';"
    result=deploy.compose(["exec","-T","timescaledb","psql","-X","-qAt","-v","ON_ERROR_STOP=1","-U","meter","-d","meter","-c",sql],env,True)
    return int(result.stdout.strip())


def legacy_active():
    token=(deploy.ROOT/"secrets/metrics-token").read_text().strip()
    request=urllib.request.Request("http://127.0.0.1:8180/actuator/prometheus",headers={"Authorization":"Bearer "+token})
    with urllib.request.urlopen(request,timeout=8) as response:
        metrics=response.read().decode()
    values=re.findall(r"^meter_active_requests(?:\{[^\n]*\})?\s+([0-9.eE+-]+)\s*$",metrics,re.MULTILINE)
    if not values:
        raise RuntimeError("Cannot verify legacy in-flight gauge; refusing cutover")
    return sum(float(value) for value in values)


def verify_pause():
    # GET to auth API is harmless; no upstream request or credential is sent.
    try:
        urllib.request.urlopen("http://127.0.0.1:8182/portal/api/session",timeout=8)
    except urllib.error.HTTPError as response:
        if response.code==503 and response.headers.get("Retry-After")=="60":return
    raise RuntimeError("Edge admission pause was not observed")


def wait_legacy_quiet(env,maximum):
    deadline=time.monotonic()+maximum
    quiet_since=None
    while time.monotonic()<deadline:
        quiet=legacy_active()==0 and pending_legacy(env)==0
        if quiet:
            quiet_since=quiet_since or time.monotonic()
            # A complete quiet minute expires old local request/auth budgets and catches delayed ledger begins.
            if time.monotonic()-quiet_since>=60:return
        else:quiet_since=None
        time.sleep(2)
    raise RuntimeError("Legacy work did not settle; no process stopped")


def bootstrap(args):
    if not args.allow_admission_pause:
        raise RuntimeError("First handoff pauses new requests while existing streams finish plus a quiet60-second window. Explicit --allow-admission-pause required.")
    if not re.fullmatch(r"[0-9a-f]{40}",args.sha) or not deploy.is_immutable_image(args.image):
        raise ValueError("Full SHA and immutable control-usage image digest required")
    if not args.caddy_sse_verified:
        raise RuntimeError("Pinned edge reload/SSE fixture must pass before --caddy-sse-verified")
    deploy.STATE.mkdir(exist_ok=True)
    with (deploy.STATE/"deploy.lock").open("w") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        if (deploy.STATE/"bootstrap.json").exists() or (deploy.STATE/"release.json").exists():
            raise RuntimeError("Deployment state already exists; inspect/reconcile it before bootstrap")
        if deploy.run(["git","rev-parse","HEAD"],capture_output=True).stdout.strip()!=args.sha:
            raise RuntimeError("Deploy checkout must match release image SHA")
        deploy.preflight_resources()
        release={"sha":args.sha,"image":args.image}
        manifest={"phase":"preparing","active":"legacy","slots":{"blue":release},"audit":{"transport":"jdbc"}}
        env=deploy.release_env(manifest)
        deploy.prepare_image(args.image,args.sha)
        deploy.run(["python3","scripts/migrate.py","--apply"])
        deploy.install_assets(args.image,args.sha)
        deploy.compose(["up","-d","--no-deps","app-blue"],env)
        deploy.wait_ready("blue",args.sha)
        before=deploy.CADDY.read_text()
        deploy.write_json(deploy.STATE/"bootstrap-previous-edge.json",{"config":before})
        deploy.write_json(deploy.STATE/"release.json",manifest)
        switched=False
        try:
            deploy.reload_edge(maintenance_config(before),env)
            verify_pause()
            manifest["phase"]="bootstrap-paused"
            deploy.write_json(deploy.STATE/"release.json",manifest)
            wait_legacy_quiet(env,args.maximum_pause_seconds)
            # Retire the legacy admission process before allowing new traffic. Its gauge
            # cannot see requests still reading bodies or blocked before ledger admission.
            # Once retirement is attempted, require operator recovery on any failure.
            switched=True
            manifest["phase"]="bootstrap-retiring-legacy"
            deploy.write_json(deploy.STATE/"release.json",manifest)
            deploy.compose(["stop","app"],env)
            manifest["phase"]="bootstrap-switching"
            deploy.write_json(deploy.STATE/"release.json",manifest)
            deploy.reload_edge(deploy.render_config(before,"blue",args.sha),env)
            deploy.verify_edge(args.sha)
            manifest.update(active="blue",phase="stable")
            deploy.write_json(deploy.STATE/"release.json",manifest)
            deploy.write_json(deploy.STATE/"bootstrap.json",{"verified":True,"caddySseVerified":True,"initialSha":args.sha,"completedAt":time.time()})
            print("Bootstrap complete. Existing edge/tunnel processes retained; legacy application retired after quiet window.")
        except Exception:
            if not switched:
                deploy.reload_edge(before,env)
                manifest["phase"]="bootstrap-aborted-legacy-restored"
                deploy.write_json(deploy.STATE/"release.json",manifest)
            # After blue receives requests, never silently restore lease-unaware legacy admission.
            raise


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sha",required=True)
    parser.add_argument("--image",required=True)
    parser.add_argument("--allow-admission-pause",action="store_true")
    parser.add_argument("--caddy-sse-verified",action="store_true")
    parser.add_argument("--maximum-pause-seconds",type=int,default=720)
    bootstrap(parser.parse_args())


if __name__=="__main__":main()
