#!/bin/sh
# Run interactively in the Coder terminal. OAuth approval belongs to the user.
set -eu

if [ -x "$HOME/.local/bin/claude" ]; then
    claude_binary="$HOME/.local/bin/claude"
elif command -v claude >/dev/null 2>&1; then
    claude_binary=$(command -v claude)
else
    printf '%s\n' 'Claude Code is not installed on this machine.' >&2
    exit 1
fi

umask 077
CLAUDE_CONFIG_DIR="$HOME/.config/subscription-meter-claude"
export CLAUDE_CONFIG_DIR
mkdir -p "$CLAUDE_CONFIG_DIR"
chmod 700 "$CLAUDE_CONFIG_DIR"
cd "$CLAUDE_CONFIG_DIR"

# These changes affect this child process only, not the calling terminal.
unset ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN ANTHROPIC_BASE_URL
unset CLAUDE_CODE_OAUTH_TOKEN CLAUDE_CODE_OAUTH_REFRESH_TOKEN CLAUDE_CODE_OAUTH_SCOPES

printf '%s\n' 'Open the authorization link in a private browser window and select the intended account.'
printf '%s\n' 'Keep the generated token private. This command does not enable the gateway upstream.'
exec "$claude_binary" setup-token
