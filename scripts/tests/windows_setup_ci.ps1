# Exercise both elevated defaults and a standard user's recovery of files left
# by an administrator setup. All homes and credentials below are disposable.
$ErrorActionPreference = 'Stop'
$name = 'cu-setup-test'
$password = ConvertTo-SecureString ('Cu!' + [guid]::NewGuid().ToString('N') + '9a') -AsPlainText -Force
$credential = [pscredential]::new("$env:COMPUTERNAME\$name", $password)
$root = Join-Path $env:SystemDrive ('control-usage-ci-' + [guid]::NewGuid().ToString('N'))
$python = (Get-Command python.exe).Source
$node = (Get-Command node.exe).Source
$repo = (Get-Location).Path
try {
    New-LocalUser -Name $name -Password $password -Description 'Disposable installer CI account' | Out-Null
    $env:CONTROL_USAGE_ELEVATED_TEST = '1'
    & $python -m unittest discover -s scripts/tests -p test_terminal_setup.py -v
    if ($LASTEXITCODE -ne 0) { throw 'Elevated Windows installer checks failed' }
    Remove-Item Env:CONTROL_USAGE_ELEVATED_TEST
    New-Item -ItemType Directory -Path $root | Out-Null
    & icacls.exe $root /grant "${name}:(OI)(CI)M" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot grant fixture directory access' }
    & icacls.exe $repo /grant "${name}:(OI)(CI)RX" /T /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot grant checkout read access' }
    $fixture = Join-Path $root 'prior-admin-home'
    $claude = Join-Path $fixture '.claude'
    $state = Join-Path $fixture '.config/control-usage'
    New-Item -ItemType Directory -Path $claude, $state -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $claude 'settings.json'), '{"model":"keep","env":{"OTHER":"preserved"}}')
    [IO.File]::WriteAllText((Join-Path $claude '.credentials.json'), 'untouched fixture')
    [IO.File]::WriteAllText((Join-Path $state 'direct-setup.lock'), '0')
    & icacls.exe $fixture /grant "${name}:(OI)(CI)F" /T /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot grant fixture repair access' }
    & icacls.exe $fixture /setowner '*S-1-5-32-544' /T /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot seed administrator-owned fixture' }
    $child = @'
import os, subprocess, sys, ctypes, shutil
from pathlib import Path
from ctypes import wintypes
ctypes.windll.kernel32.GetCurrentProcess.restype = wintypes.HANDLE
ctypes.windll.advapi32.OpenProcessToken.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE)]
ctypes.windll.userenv.GetUserProfileDirectoryW.argtypes = [wintypes.HANDLE, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
ctypes.windll.kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
# Build the actual logged-on user's profile environment before starting PowerShell.
token = ctypes.c_void_p()
if not ctypes.windll.advapi32.OpenProcessToken(ctypes.windll.kernel32.GetCurrentProcess(), 8, ctypes.byref(token)):
    raise ctypes.WinError()
try:
    size = ctypes.c_ulong(32768)
    profile = ctypes.create_unicode_buffer(size.value)
    if not ctypes.windll.userenv.GetUserProfileDirectoryW(token, profile, ctypes.byref(size)):
        raise ctypes.WinError()
finally:
    ctypes.windll.kernel32.CloseHandle(token)
os.environ.update(USERPROFILE=profile.value, APPDATA=str(Path(profile.value)/'AppData/Roaming'),
    LOCALAPPDATA=str(Path(profile.value)/'AppData/Local'), TEMP=str(Path(__file__).parent), TMP=str(Path(__file__).parent))
windows = ctypes.create_unicode_buffer(32768)
if not ctypes.windll.kernel32.GetWindowsDirectoryW(windows, len(windows)): raise ctypes.WinError()
os.environ.update(SystemRoot=windows.value, WINDIR=windows.value, COMSPEC=str(Path(windows.value)/'System32/cmd.exe'))
os.environ['PSModulePath'] = str(Path(windows.value)/'System32/WindowsPowerShell/v1.0/Modules')
os.environ['PATH'] = str(Path(windows.value)/'System32') + ';' + str(Path(windows.value)/'System32/WindowsPowerShell/v1.0') + ';' + os.path.expandvars(os.environ.get('PATH', ''))
os.chdir(sys.argv[1])
os.environ.pop('CONTROL_USAGE_ELEVATED_TEST', None)
os.environ['CONTROL_USAGE_ADMIN_OWNED_HOME'] = sys.argv[3]
try:
    for command in ([sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts/tests', '-p', 'test_terminal_setup.py', '-v'],
                    [sys.argv[2], '--test', 'frontend/tests/setup-command.test.mjs']):
        result = subprocess.run(command)
        if result.returncode: sys.exit(result.returncode)
finally:
    # Private ACLs intentionally exclude the administrator group; clean up as
    # the same standard user before the outer harness removes that account.
    shutil.rmtree(sys.argv[3])
'@
    # CreateProcessWithLogonW limits the command line to 1024 characters.
    $childPath = Join-Path $root 'checks.py'
    Set-Content -LiteralPath $childPath -Value $child -Encoding UTF8
    Start-Service seclogon
    $process = Start-Process -FilePath $python -Credential $credential -LoadUserProfile -UseNewEnvironment -ArgumentList @('-u', "`"$childPath`"", "`"$repo`"", "`"$node`"", "`"$fixture`"") -WorkingDirectory $root -RedirectStandardOutput "$root\stdout.log" -RedirectStandardError "$root\stderr.log" -Wait -PassThru
    Get-Content -LiteralPath "$root\stdout.log"
    Get-Content -LiteralPath "$root\stderr.log"
    if ($process.ExitCode -ne 0) { throw "Native Windows checks failed: $($process.ExitCode)" }
} finally {
    Remove-Item Env:CONTROL_USAGE_ELEVATED_TEST -ErrorAction SilentlyContinue
    Remove-LocalUser -Name $name -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
