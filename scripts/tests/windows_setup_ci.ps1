# Run installer tests as a normal user on the disposable hosted Windows runner.
# Elevated runners otherwise create files owned by Administrators, which the
# installer deliberately refuses. No production credentials or user homes used.
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
    New-Item -ItemType Directory -Path $root | Out-Null
    & icacls.exe $root /grant "${name}:(OI)(CI)M" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot grant fixture directory access' }
    & icacls.exe $repo /grant "${name}:(OI)(CI)RX" /T /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot grant checkout read access' }
    $child = @'
$ErrorActionPreference = 'Stop'
$env:PSModulePath = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\Modules"
$env:TEMP = '__ROOT__'
$env:TMP = '__ROOT__'
Set-Location -LiteralPath '__REPO__'
& '__PYTHON__' -m unittest discover -s scripts/tests -p test_terminal_setup.py -v
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& '__NODE__' --test frontend/tests/setup-command.test.mjs
exit $LASTEXITCODE
'@
    $child = $child.Replace('__ROOT__', $root.Replace("'", "''")).Replace('__REPO__', $repo.Replace("'", "''")).Replace('__PYTHON__', $python.Replace("'", "''")).Replace('__NODE__', $node.Replace("'", "''"))
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($child))
    Start-Service seclogon
    $process = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Credential $credential -LoadUserProfile -ArgumentList @('-NoProfile', '-NonInteractive', '-EncodedCommand', $encoded) -WorkingDirectory $root -RedirectStandardOutput "$root\stdout.log" -RedirectStandardError "$root\stderr.log" -Wait -PassThru
    Get-Content -LiteralPath "$root\stdout.log"
    Get-Content -LiteralPath "$root\stderr.log"
    if ($process.ExitCode -ne 0) { throw "Native Windows checks failed: $($process.ExitCode)" }
} finally {
    Remove-LocalUser -Name $name -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
