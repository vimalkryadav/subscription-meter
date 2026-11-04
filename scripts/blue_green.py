#!/usr/bin/env python3
"""Serialized single-host release switch. Deliberately refuses the legacy bootstrap."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / "deploy/state"
RUNTIME_ROOT = Path(os.environ.get("CONTROL_USAGE_RUNTIME_ROOT", str(ROOT)))
CADDY = RUNTIME_ROOT / "deploy/public-gateway.Caddyfile"
PORT = {"blue": 8183, "green": 8184}


def run(args, **kwargs):
    if args and args[0]=="docker" and os.environ.get("CONTROL_USAGE_DOCKER_SUDO")=="1":
        names="BLUE_IMAGE,BLUE_RELEASE,GREEN_IMAGE,GREEN_RELEASE,AUDIT_WORKER_IMAGE,METER_AUDIT_TRANSPORT,METER_AUDIT_KAFKA_BOOTSTRAP_SERVERS,METER_AUDIT_KAFKA_TOPIC,METER_AUDIT_KAFKA_GROUP,METER_AUDIT_KAFKA_PARTITIONS"
        args=["sudo","-n","--preserve-env="+names,*args]
    return subprocess.run(args, cwd=ROOT, check=True, text=True, **kwargs)


def compose(args, env=None, capture=False):
    return run(["docker", "compose", "--profile", "public", "--profile", "bluegreen", "--profile", "audit", "-f", "compose.yaml", "-f", "compose.bluegreen.yaml", "-f", "compose.audit.yaml", *args],
               env=env, capture_output=capture)


def api(color, action="", timeout=8):
    # Credentials stay out of command lines, output and release manifests.
    token = (ROOT / "secrets/deploy-token").read_text().strip()
    request = urllib.request.Request(f"http://127.0.0.1:{PORT[color]}/internal/deployment" + action,
                                     headers={"Authorization": "Bearer " + token},
                                     method="POST" if action else "GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def write_json(path, value):
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(value, indent=2) + "\n")
    os.replace(temp, path)


def release_env(manifest):
    env = os.environ.copy()
    audit=manifest.get("audit",{"transport":"jdbc"})
    transport=audit.get("transport","jdbc")
    if transport not in ("jdbc","kafka"):
        raise ValueError("Unknown audit transport")
    env["METER_AUDIT_TRANSPORT"]=transport
    if transport=="kafka":
        image=audit.get("workerImage","")
        if not re.fullmatch(r"ghcr\.io/vimalkryadav/control-usage@sha256:[0-9a-f]{64}",image) or audit.get("contractVersion")!=1:
            raise ValueError("Kafka deployment requires a pinned shared worker image and audit contractVersion1")
        env["AUDIT_WORKER_IMAGE"]=image
        env["METER_AUDIT_KAFKA_BOOTSTRAP_SERVERS"]="audit-kafka:9092"
        env["METER_AUDIT_KAFKA_TOPIC"]=audit.get("topic","control-usage.audit.v1")
        env["METER_AUDIT_KAFKA_GROUP"]=audit.get("group","control-usage-audit-v1")
        env["METER_AUDIT_KAFKA_PARTITIONS"]=str(audit.get("partitions",1))
    for color, release in manifest.get("slots", {}).items():
        env[color.upper()+"_IMAGE"] = release["image"]
        env[color.upper()+"_RELEASE"] = release["sha"]
    return env


def render_config(template, color, sha):
    if not re.fullmatch("[0-9a-f]{40}", sha) or color not in PORT:
        raise ValueError("Invalid release or color")
    # Preserve existing path allowlist/security headers and the same Caddy process/listener.
    text = re.sub(r"reverse_proxy (?:app|app-blue|app-green):8180", "reverse_proxy app-"+color+":8180", template)
    text = re.sub(r"(handle / \{\s*root \* /srv\s*)(?:rewrite \* /releases/[0-9a-f]{40}/index.html\s*)?",
                  r"\1rewrite * /releases/"+sha+"/index.html\n        ", text)
    if "reverse_proxy app-"+color+":8180" not in text or "/releases/"+sha+"/index.html" not in text:
        raise ValueError("Unrecognized Caddy template; review manually")
    return text


def install_assets(image, sha):
    root = RUNTIME_ROOT / "src/main/resources/static"
    asset_target = root / "assets/releases" / sha
    page_target = root / "releases" / sha
    if asset_target.exists() or page_target.exists():
        # A SHA is immutable; repeat deployment reuses already published content.
        if not (asset_target.is_dir() and (page_target / "index.html").is_file()):
            raise RuntimeError("Incomplete existing release assets")
        return
    container = run(["docker", "create", image], capture_output=True).stdout.strip()
    try:
        with tempfile.TemporaryDirectory() as folder:
            jar = Path(folder) / "app.jar"
            run(["docker", "cp", container+":/app/app.jar", str(jar)], capture_output=True)
            with zipfile.ZipFile(jar) as archive:
                prefix = "BOOT-INF/classes/static/assets/"
                staged = Path(folder) / "assets"
                staged.mkdir()
                for name in archive.namelist():
                    if not name.startswith(prefix) or name.endswith("/"):
                        continue
                    relative = Path(name[len(prefix):])
                    if relative.is_absolute() or ".." in relative.parts:
                        raise ValueError("Unsafe asset path")
                    dest = staged / relative
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(archive.read(name))
                html = archive.read("BOOT-INF/classes/static/index.html").decode()
                asset_target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copytree(staged, asset_target)
                page_target.mkdir(parents=True)
                (page_target / "index.html").write_text(html.replace("/assets/", "/assets/releases/"+sha+"/"))
    finally:
        run(["docker", "rm", container], capture_output=True)


def reload_edge(config, env):
    candidate = STATE / "candidate.Caddyfile"
    candidate.write_text(config)
    # Validation uses the same pinned image, isolated from the running listener.
    image = compose(["images", "-q", "public-edge"], env, True).stdout.strip()
    if not image:
        raise RuntimeError("Running public edge image not found")
    run(["docker", "run", "--rm", "--entrypoint", "caddy", "-v", str(candidate)+":/candidate:ro",
         image, "validate", "--config", "/candidate", "--adapter", "caddyfile"], capture_output=True)
    # Existing live deployment mounts a single file. Preserve its inode deliberately.
    # Deploy lock prevents competing writers; SIGUSR1 is only sent after a complete fsync.
    with CADDY.open("w") as output:
        output.write(config)
        output.flush()
        os.fsync(output.fileno())
    compose(["kill", "--signal", "SIGUSR1", "public-edge"], env, True)


def wait_ready(color, sha):
    deadline = time.monotonic()+90
    while time.monotonic()<deadline:
        try:
            state = api(color)
            if state["ready"] and state["release"] == sha:
                return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError("Inactive release failed readiness; existing route retained")


def wait_drained(color, maximum):
    deadline = time.monotonic()+maximum
    while time.monotonic()<deadline:
        status=api(color)
        if status.get("unsettled",0)>0:
            raise RuntimeError("Finalization failures require reconciliation; retaining old container")
        if status["active"] == 0:
            return
        time.sleep(2)
    raise RuntimeError("Drain deadline exceeded; old container retained, never force-stopped")


def verify_edge(sha):
    # Release marker is generated from the immutable index artifact during installation.
    with urllib.request.urlopen("http://127.0.0.1:8182/", timeout=10) as response:
        if ("/assets/releases/"+sha+"/").encode() not in response.read():
            raise RuntimeError("Edge did not load expected release")


def preflight_resources():
    memory=Path("/proc/meminfo")
    if memory.exists():
        available=re.search(r"^MemAvailable:\s+(\d+) kB",memory.read_text(),re.MULTILINE)
        if not available or int(available[1])<3*1024*1024:
            raise RuntimeError("Less than3GiB memory headroom; preserve shared-host workloads")
    storage=run(["docker","info","--format","{{.DockerRootDir}}"],capture_output=True).stdout.strip()
    for path in {ROOT,Path(storage)}:
        if path.exists() and shutil.disk_usage(path).free<10*1024**3:
            raise RuntimeError("Less than10GiB free storage; no automatic pruning is allowed")


def verify_shared_audit(manifest,env):
    """Normal app CD validates the existing audit cohort; it never recreates shared services."""
    audit=manifest.get("audit",{"transport":"jdbc"})
    if audit.get("transport","jdbc")=="jdbc":return
    for service in ("audit-kafka","audit-worker"):
        container=compose(["ps","-q",service],env,True).stdout.strip()
        if not container:
            raise RuntimeError("Required shared audit service is absent: "+service)
        # Select only nonsecret fields. Never inspect or print container environment values.
        running=run(["docker","inspect","--format","{{.State.Running}}",container],capture_output=True).stdout.strip()
        if running!="true":raise RuntimeError("Shared audit service is not running: "+service)
        if service=="audit-worker":
            image=run(["docker","inspect","--format","{{.Config.Image}}",container],capture_output=True).stdout.strip()
            if image!=audit["workerImage"]:
                raise RuntimeError("Running audit worker differs from the pinned deployment cohort")
            contract=run(["docker","image","inspect","--format",'{{index .Config.Labels "dev.control-usage.audit-contract"}}',image],capture_output=True).stdout.strip()
            if contract!="1":raise RuntimeError("Worker does not declare supported audit contract1")


def deploy(args):
    STATE.mkdir(exist_ok=True)
    with (STATE / "deploy.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        bootstrap = STATE / "bootstrap.json"
        if not bootstrap.exists() or not json.loads(bootstrap.read_text()).get("verified"):
            raise RuntimeError("Bootstrap not verified. Follow deploy/BLUE_GREEN.md; no automatic legacy cutover.")
        path = STATE / "release.json"
        manifest = json.loads(path.read_text())
        if manifest.get("phase") != "stable":
            raise RuntimeError("Previous deployment unfinished; inspect manifest and edge before recovery")
        preflight_resources()
        old = manifest["active"]
        verify_edge(manifest["slots"][old]["sha"])
        new = "green" if old == "blue" else "blue"
        if args.rollback:
            previous = manifest.get("previous")
            if not previous or previous["color"] != new:
                raise RuntimeError("No compatible previous release recorded")
            release = previous["release"]
        else:
            if not re.fullmatch(r"ghcr\.io/vimalkryadav/control-usage@sha256:[0-9a-f]{64}", args.image or ""):
                raise ValueError("An immutable control-usage GHCR image digest is required")
            if not re.fullmatch(r"[0-9a-f]{40}", args.sha or ""):
                raise ValueError("Full Git SHA required")
            release = {"image": args.image, "sha": args.sha}
        manifest["slots"][new] = release
        env = release_env(manifest)
        verify_shared_audit(manifest,env)
        if not args.rollback:
            checked_out=run(["git","rev-parse","HEAD"],capture_output=True).stdout.strip()
            if checked_out!=release["sha"]:
                raise RuntimeError("Deployment checkout must match image SHA before migrations")
        run(["docker", "pull", release["image"]], capture_output=True)
        inspected=json.loads(run(["docker","image","inspect",release["image"]],capture_output=True).stdout)[0]
        if inspected.get("Config",{}).get("Labels",{}).get("org.opencontainers.image.revision")!=release["sha"]:
            raise RuntimeError("Image revision label does not match requested release")
        if manifest.get("audit",{}).get("transport")=="kafka" and inspected.get("Config",{}).get("Labels",{}).get("dev.control-usage.audit-contract")!="1":
            raise RuntimeError("Gateway and shared worker audit contracts differ")
        if not args.rollback:
            run(["python3", "scripts/migrate.py", "--apply"])
        install_assets(release["image"], release["sha"])
        # Persist intent before changing runtime. Interrupted deployments require operator reconciliation.
        manifest.update(phase="preparing", candidate=new)
        write_json(path, manifest)
        compose(["up", "-d", "--no-deps", "app-"+new], env)
        try:
            api(new,"/resume")
        except Exception:
            pass
        wait_ready(new, release["sha"])
        before = CADDY.read_text()
        write_json(STATE / "previous-edge.json", {"config": before})
        manifest["phase"] = "switching"
        write_json(path, manifest)
        try:
            reload_edge(render_config(before,new,release["sha"]),env)
            verify_edge(release["sha"])
        except Exception:
            reload_edge(before,env)
            raise
        manifest.update(phase="draining",active=new,previous={"color":old,"release":manifest["slots"][old]})
        write_json(path,manifest)
        api(old,"/drain")
        wait_drained(old,args.drain_seconds)
        compose(["stop","app-"+old],env)
        manifest.update(phase="stable")
        manifest.pop("candidate",None)
        write_json(path,manifest)
        print("Release switched and previous application drained. Tunnel and shared services unchanged.")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image")
    parser.add_argument("--sha")
    parser.add_argument("--rollback",action="store_true")
    parser.add_argument("--drain-seconds",type=int,default=370)
    args=parser.parse_args()
    deploy(args)


if __name__ == "__main__":
    main()
