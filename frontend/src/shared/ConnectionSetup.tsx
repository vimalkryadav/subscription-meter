import { useState } from 'react';
import './connection.css';
import installerUrl from '../../setup/setup-claude.py?url';

import { setupCommand, quote, psQuote } from './setup-command';

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
  const [windows, setWindows] = useState(/Windows/i.test(navigator.userAgent));
  const origin = new URL(gateway).origin;
  const script = new URL(installerUrl, window.location.href).href;
  const command = (flags: string) => setupCommand(script, flags, windows);
  return <div>
    <h3>Set up once</h3>
    <div className="setup-platform" role="group" aria-label="Terminal operating system">
      <button type="button" className="cu-button" aria-pressed={!windows} onClick={() => setWindows(false)}>macOS / Linux / WSL</button>
      <button type="button" className="cu-button" aria-pressed={windows} onClick={() => setWindows(true)}>Windows PowerShell</button>
    </div>
    <p>Copy this command into your terminal, then enter your platform member key at the hidden prompt. Setup saves the gateway in your user-level Claude settings. Your key is never included in the command.</p>
    <Command key={String(windows)} label="Copy setup command" value={command(`--url ${(windows ? psQuote : quote)(origin)}`)} />
    <p>After setup, open any terminal and run:</p><pre>claude</pre>
    <p>{windows ? 'Requires Claude Code and Python 3 with the py launcher. Run in Windows PowerShell (5.1 or later); no administrator terminal or execution-policy change is needed.' : 'Requires Claude Code, Python 3 and curl. WSL uses its own Linux home and Claude installation.'} All terminal sessions reuse your member key and share its limits. Restart existing Claude sessions, then use <code>/status</code> to check the gateway.</p>
    <p>Your existing settings are backed up privately before merging the gateway configuration. Saved provider logins, hooks and permissions are preserved. Ordinary Claude sessions use control-usage until you undo setup.</p>
    <details><summary>Undo setup or update your key</summary>
      <p>After rotating your key or changing the tunnel URL, copy the setup command again. Undo restores prior gateway/auth settings and preserves later edits, unrelated settings and conversation history. It does not revoke your platform key.</p>
      <Command key={String(windows)} label="Copy undo command" value={command('--remove')} />
    </details>
    {!windows && <details><summary>Optional: keep personal and work profiles separate</summary>
      <p>Choose this instead of the default setup if ordinary <code>claude</code> should keep using your personal account. If you already ran the default setup, undo it first. This option creates a separate profile and launcher:</p>
      <Command label="Copy separate-profile command" value={command(`--separate-profile --url ${quote(origin)}`)} />
      <pre>~/.local/bin/claude-work</pre>
      <Command label="Copy separate-profile removal command" value={command('--separate-profile --remove')} />
    </details>}
    <details><summary>Inspect or download the installer</summary>
      <p>You can inspect the Python script before running it. Settings and the backup contain private credentials; do not share or commit them. If you use <code>CLAUDE_CONFIG_DIR</code>, unset it before default setup. Project or managed settings may override user settings; check <code>/status</code>.</p>
      <a className="cu-button" href={installerUrl} download="control-usage-setup.py">Download terminal setup</a>
      <pre>{windows ? `py -3 "$env:USERPROFILE\\Downloads\\control-usage-setup.py" --url ${psQuote(origin)}` : `python3 ~/Downloads/control-usage-setup.py --url ${quote(origin)}`}</pre>
    </details>
  </div>;
}
