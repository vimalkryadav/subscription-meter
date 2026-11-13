import { useState } from 'react';
import './connection.css';
import installerUrl from '../../setup/setup-claude.py?url';
import { setupCommand, quote, psQuote } from './setup-command';

function Command({ value, label }: { value: string; label: string }) {
  const [message, setMessage] = useState('');
  const [expanded, setExpanded] = useState(false);
  async function copy() {
    try { await navigator.clipboard.writeText(value); setMessage('Copied'); }
    catch { setExpanded(true); setMessage('Copy is unavailable. Select the command below to copy it.'); }
  }
  return <div className="setup-command"><div className="setup-command-actions"><button type="button" className="cu-button" onClick={copy}>{label}</button><span className="setup-copy-status" role="status" aria-live="polite">{message}</span></div>
    <details open={expanded} onToggle={event => setExpanded(event.currentTarget.open)}><summary>View command</summary><pre tabIndex={0} aria-label={label.replace('Copy', 'Terminal')}>{value}</pre></details></div>;
}

export default function ConnectionSetup({ gateway }: { gateway: string }) {
  const [windows, setWindows] = useState(/Windows/i.test(navigator.userAgent));
  const origin = new URL(gateway).origin;
  const script = new URL(installerUrl, window.location.href).href;
  const command = (flags: string) => setupCommand(script, flags, windows);
  return <div className="connection-setup">
    <p className="setup-intro">Set up once. New terminal sessions will use your saved member key.</p>
    <div className="setup-platform" role="group" aria-label="Terminal operating system">
      <button type="button" className="cu-button" aria-pressed={!windows} onClick={() => setWindows(false)}>macOS / Linux / WSL</button>
      <button type="button" className="cu-button" aria-pressed={windows} onClick={() => setWindows(true)}>Windows PowerShell</button>
    </div>
    <ol className="setup-steps"><li><span className="setup-step-title">Run setup in your terminal</span><Command key={String(windows)} label="Copy setup command" value={command(`--url ${(windows ? psQuote : quote)(origin)}`)} /></li>
      <li><span className="setup-step-title">Paste your member key at the hidden prompt</span><p>The key is never included in the command. Setup saves it in your user-level Claude settings.</p></li>
      <li><span className="setup-step-title">Open Claude Code</span><p>Run <code>claude</code> in a new terminal, then check <code>/status</code>. Restart existing Claude sessions.</p></li></ol>
    <details><summary>Requirements and existing settings</summary>
      <p>{windows ? 'Requires Claude Code and Python 3 with the py launcher. Run in Windows PowerShell (5.1 or later); no administrator terminal or execution-policy change is needed.' : 'Requires Claude Code, Python 3 and curl. WSL uses its own Linux home and Claude installation.'} All terminal sessions reuse your member key and share its limits.</p>
      <p>Your existing settings are backed up privately before merging the gateway configuration. Saved provider logins, hooks and permissions are preserved. Ordinary Claude sessions use control-usage until you undo setup.</p>
      <p>If you use <code>CLAUDE_CONFIG_DIR</code>, unset it before default setup. Project or managed settings may override user settings; check <code>/status</code>.</p>
    </details>
    <details><summary>Update your key or undo setup</summary>
      <p>After replacing your key or changing the tunnel URL, run the setup command again. Undo restores prior gateway/auth settings and preserves later edits, unrelated settings and conversation history. It does not revoke your platform key.</p>
      <Command key={String(windows)} label="Copy undo command" value={command('--remove')} />
    </details>
    {!windows && <details><summary>Keep personal and work profiles separate</summary>
      <p>Choose this instead of the default setup if ordinary <code>claude</code> should keep using your personal account. If you already ran the default setup, undo it first. This option creates a separate profile and launcher:</p>
      <Command label="Copy separate-profile command" value={command(`--separate-profile --url ${quote(origin)}`)} />
      <p>Then run <code>~/.local/bin/claude-work</code> for work sessions.</p>
      <Command label="Copy separate-profile removal command" value={command('--separate-profile --remove')} />
    </details>}
    <details><summary>Inspect or download the installer</summary>
      <p>You can inspect the Python script before running it. Settings and the backup contain private credentials; do not share or commit them.</p>
      <a className="cu-button" href={installerUrl} download="control-usage-setup.py">Download terminal setup</a>
      <pre tabIndex={0}>{windows ? `py -3 "$env:USERPROFILE\\Downloads\\control-usage-setup.py" --url ${psQuote(origin)}` : `python3 ~/Downloads/control-usage-setup.py --url ${quote(origin)}`}</pre>
    </details>
  </div>;
}
