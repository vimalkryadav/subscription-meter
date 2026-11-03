"""Provision new local secrets without printing them. Never overwrites existing secrets."""
import os
from pathlib import Path
import secrets

ROOT = Path(__file__).resolve().parents[1]
directory = ROOT / "secrets"
directory.mkdir(exist_ok=True)
directory.chmod(0o700)
for name in ("admin-token", "metrics-token", "upstream-token"):
    path = directory / name
    if not path.exists():
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as file:
            file.write("" if name == "upstream-token" else secrets.token_urlsafe(48) + "\n")
    # Docker mounts individual files into containers running as unprivileged users.
    # The parent directory is 0700, preventing other host users from reading them.
    path.chmod(0o644)
env = ROOT / ".env"
if not env.exists():
    fd = os.open(env, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as file:
        file.write("DATABASE_PASSWORD=" + secrets.token_urlsafe(40) + "\n")
        file.write("UPSTREAM_MODE=subscription\nUPSTREAM_URL=https://api.anthropic.com\n")
print("Secret files ready. Provision the upstream credential before using the gateway. Credentials were not printed.")
