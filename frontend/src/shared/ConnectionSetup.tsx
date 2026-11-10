import installerUrl from '../../setup/setup-claude.py?url';

export default function ConnectionSetup({ gateway }: { gateway: string }) {
  const origin = new URL(gateway).origin;
  const command = `python3 ~/Downloads/control-usage-setup.py --url '${origin}'`;
  return <div>
    <h3>Set up once · macOS / Linux</h3>
    <p>Save the installer in your Downloads folder, then run the command below. It asks for your platform member key with hidden input and saves a private, separate Claude Code profile. Your personal Claude login stays separate.</p>
    <a className="cu-button" href={installerUrl} download="control-usage-setup.py">Download terminal setup</a>
    <pre>{command}</pre>
    <p>After setup, open any terminal and run:</p><pre>~/.local/bin/claude-work</pre>
    <p>Claude Code and Python 3 are required. The same member key works across your terminal sessions; rate and concurrency limits are shared. Use <code>/status</code> to verify the gateway.</p>
    <p>After rotating your key or changing the tunnel URL, run setup again and restart active Claude sessions. To remove the saved gateway credential and launcher while keeping conversation history:</p>
    <pre>python3 ~/Downloads/control-usage-setup.py --remove</pre>
  </div>;
}
