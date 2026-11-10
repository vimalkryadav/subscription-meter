import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { spawnSync } from 'node:child_process';
const source = await readFile(new URL('../src/shared/setup-command.ts', import.meta.url), 'utf8');
const { setupCommand, quote, psQuote } = await import(`data:text/javascript;base64,${Buffer.from(stripTypeScriptTypes(source)).toString('base64')}`);

test('setup commands quote paths, clean up downloads and omit member credentials', () => {
  const script = "https://example.com/assets/setup's.py";
  const unix = setupCommand(script, '--remove', false);
  if (process.platform !== 'win32') assert.equal(spawnSync('bash', ['-n'], { input: unix, encoding: 'utf8' }).status, 0);
  assert(unix.includes(quote(script)));
  assert(unix.includes("trap 'rm -rf"));
  const windows = setupCommand(script, '--remove', true);
  assert(windows.includes(psQuote(script)));
  assert(windows.includes('finally'));
  assert(!windows.includes('ExecutionPolicy'));
  assert(!windows.includes('sm_'));
});

test('native PowerShell executes copied setup/undo commands and removes temporary downloads', { skip: process.platform !== 'win32' }, () => {
  for (const flags of ["--url 'https://example.com'", '--remove']) {
    const command = setupCommand("https://example.com/assets/setup's.py", flags, true);
    const harness = `
$ErrorActionPreference = 'Stop'
function Invoke-WebRequest {
 param([switch]$UseBasicParsing, [int]$TimeoutSec, [string]$Uri, [string]$OutFile, [string]$ErrorAction)
 if ($Uri -ne "https://example.com/assets/setup's.py") { throw 'URI changed' }
 $global:downloadPath = $OutFile
 [IO.File]::WriteAllText($OutFile, 'fixture')
}
function py { $global:pythonArgs = @($args); $global:LASTEXITCODE = 0 }
& ([ScriptBlock]::Create($env:CONTROL_USAGE_TEST_COMMAND))
if ($pythonArgs[0] -ne '-3' -or $pythonArgs[1] -ne $downloadPath) { throw 'wrong Python invocation' }
if (($pythonArgs[2..($pythonArgs.Length - 1)] -join ' ') -ne $env:CONTROL_USAGE_EXPECTED_FLAGS) { throw 'wrong installer arguments' }
if (Test-Path -LiteralPath $downloadPath) { throw 'temporary download remains' }
`;
    const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', harness], {
      env: { ...process.env, CONTROL_USAGE_TEST_COMMAND: command, CONTROL_USAGE_EXPECTED_FLAGS: flags.replaceAll("'", ''), PSModulePath: process.env.SystemRoot + '\\System32\\WindowsPowerShell\\v1.0\\Modules' }, encoding: 'utf8', timeout: 30000,
    });
    assert.equal(result.status, 0, result.stderr);
  }
});
