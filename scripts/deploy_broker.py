#!/usr/bin/env python3
"""Explicit deploy of the shared OpenAI key broker. Normal app releases never recreate it.

Deploys only the image of the currently active release, so its V010 schema is already applied.
Holds the release lock, waits for the container health check, and restores the previous image
if the new one does not become healthy. Never prints environment values or secrets.
"""
import argparse
import fcntl
import json
import os
import time
import blue_green as bg

SERVICE = "openai-broker"


def compose(args, env, capture=False):
    return bg.run(["docker", "compose", "--profile", "broker", "-f", "compose.yaml", "-f", "compose.bluegreen.yaml",
                   "-f", "compose.audit.yaml", *args], env=env, capture_output=capture)


def container(env):
    return compose(["ps", "-a", "-q", SERVICE], env, True).stdout.strip()


def running_image(env):
    cid = container(env)
    if not cid:
        return None
    return bg.run(["docker", "inspect", "--format", "{{.Config.Image}}", cid], capture_output=True).stdout.strip() or None


def health(env):
    cid = container(env)
    if not cid:
        return "absent"
    return bg.run(["docker", "inspect", "--format", "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}", cid],
                  capture_output=True).stdout.strip()


def wait_healthy(env, timeout, sleep=time.sleep, clock=time.monotonic):
    deadline = clock() + timeout
    while clock() < deadline:
        state = health(env)
        if state == "healthy":
            return True
        if state in ("unhealthy", "absent"):
            return False
        sleep(3)
    return False


def start(image, env):
    env = dict(env, BROKER_IMAGE=image)
    compose(["up", "-d", "--no-deps", SERVICE], env)
    return env


def deploy(image, sha, timeout=120, sleep=time.sleep, clock=time.monotonic):
    bg.STATE.mkdir(exist_ok=True)
    with (bg.STATE / "deploy.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        path = bg.STATE / "release.json"
        manifest = json.loads(path.read_text())
        if manifest.get("phase") != "stable":
            raise RuntimeError("Release in progress or unfinished; deploy the broker after it is stable")
        active = manifest["slots"][manifest["active"]]
        if sha != active.get("sha") or image != active.get("image"):
            raise RuntimeError("Deploy the broker from the active release's image and SHA only")
        bg.prepare_image(image, sha)
        env = os.environ.copy()
        previous = running_image(env)
        if previous == image and health(env) == "healthy":
            print("Key broker already runs this image. No runtime changes made.")
            return "unchanged"
        start(image, env)
        if wait_healthy(env, timeout, sleep, clock):
            manifest["broker"] = {"image": image, "sha": sha}
            bg.write_json(path, manifest)
            print("Key broker deployed and healthy.")
            return "deployed"
        if previous and previous != image:
            start(previous, env)
            restored = wait_healthy(env, timeout, sleep, clock)
            raise RuntimeError("New key broker was not healthy; previous image " + ("restored" if restored else "restore FAILED, inspect now"))
        raise RuntimeError("Key broker was not healthy and no previous image exists; OpenAI calls are unavailable")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--image", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    deploy(args.image, args.sha, args.timeout)


if __name__ == "__main__":
    main()
