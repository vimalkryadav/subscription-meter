"""Save an explicitly supplied upstream credential without echoing it."""
from getpass import getpass
from pathlib import Path


def main():
    path = Path(__file__).resolve().parents[1] / "secrets/upstream-token"
    if not path.is_file():
        raise SystemExit("Run scripts/bootstrap.py first to provision the secret files.")
    try:
        token = getpass("Upstream credential (hidden): ").strip()
    except (EOFError, KeyboardInterrupt):
        raise SystemExit("\nCancelled. Credential unchanged.")
    if not token or any(character.isspace() for character in token):
        raise SystemExit("Enter one non-empty credential without whitespace. File unchanged.")
    if token.startswith("sm_") or token.lower().startswith("bearer"):
        raise SystemExit("Enter the raw upstream credential, not a platform key or Bearer header. File unchanged.")
    # Keep the inode and permissions: Docker mounts this existing file as a secret.
    path.write_text(token + "\n")
    print("Credential saved. Gateway mode has not been changed.")


if __name__ == "__main__":
    main()
