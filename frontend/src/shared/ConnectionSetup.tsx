import { useState } from 'react';
import installerUrl from '../../setup/setup-claude.py?url';

const quote = (value: string) => "'" + value.replaceAll("'", "'\\''") + "'";

function Command({ value, label }: { value: string; label: string }) {
  const [message, setMessage] = useState('');
  async function copy() {
    try { await navigator.clipboard.writeText(value); setMessage('Copied'); }
    catch { setMessage('Select and copy the command below.'); }
  }
  return <div><button type="button" className="cu-button" onClick={copy}>{label}</button>
    <span role="status" aria-live="polite">{message && ` ${message}`}</span><pre>{value}</pre></div>;
}

export default function ConnectionSetup({ gateway }: { gateway: string }) {
  const origin = new URL(gateway).origin;
  const script = new URL(installerUrl, window.location.href).href;
  function command(flags: string) {
    return `(\n  setup_dir="$(mktemp -d)" || exit 1\n  trap 'rm -rf "$setup_dir"' EXIT\n  curl --fail --silent --show-error --proto '=https' --connect-timeout 15 --max-time 60 ${quote(script)} -o "$setup_dir/setup.py" &&\n  python3 "$setup_dir/setup.py" ${flags}\n)`;
  }
  return <div>
    <h3>Set up once · macOS / Linux</h3>
    <p>Copy this command into your terminal, then enter your platform member key at the hidden prompt. Setup saves the gateway in your user-level Claude settings. Your key is never included in the command.</p>
    <Command label="Copy setup command" value={command(`--url ${quote(origin)}`)} />
    <p>After setup, open any terminal and run:</p><pre>claude</pre>
    <p>Requires Claude Code, Python 3 and curl. All terminal sessions reuse your member key and share its limits. Restart existing Claude sessions, then use <code>/status</code> to check the gateway.</p>
    <p>Your existing settings are backed up privately before merging the gateway configuration. Saved provider logins, hooks and permissions are preserved. Ordinary Claude sessions use control-usage until you undo setup.</p>
    <details><summary>Undo setup or update your key</summary>
      <p>After rotating your key or changing the tunnel URL, copy the setup command again. Undo restores prior gateway/auth settings and preserves later edits, unrelated settings and conversation history. It does not revoke your platform key.</p>
      <Command label="Copy undo command" value={command('--remove')} />
    </details>
    <details><summary>Optional: keep personal and work profiles separate</summary>
      <p>Choose this instead of the default setup if ordinary <code>claude</code> should keep using your personal account. If you already ran the default setup, undo it first. This option creates a separate profile and launcher:</p>
      <Command label="Copy separate-profile command" value={command(`--separate-profile --url ${quote(origin)}`)} />
      <pre>~/.local/bin/claude-work</pre>
      <Command label="Copy separate-profile removal command" value={command('--separate-profile --remove')} />
    </details>
    <details><summary>Inspect or download the installer</summary>
      <p>You can inspect the Python script before running it. Settings and the backup contain private credentials; do not share or commit them. If you use <code>CLAUDE_CONFIG_DIR</code>, unset it before default setup or choose the separate profile. Project or managed settings may override user settings; check <code>/status</code>.</p>
      <a className="cu-button" href={installerUrl} download="control-usage-setup.py">Download terminal setup</a>
      <pre>{`python3 ~/Downloads/control-usage-setup.py --url ${quote(origin)}`}</pre>
    </details>
  </div>;
}
