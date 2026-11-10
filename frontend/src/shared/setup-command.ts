export const quote = (value: string) => "'" + value.replaceAll("'", "'\\''") + "'";
export const psQuote = (value: string) => "'" + value.replaceAll("'", "''") + "'";


export function setupCommand(script: string, flags: string, windows: boolean) {
    if (windows) return `& {\n  $setup = Join-Path ([IO.Path]::GetTempPath()) ('control-usage-' + [guid]::NewGuid() + '.py')\n  try {\n    Invoke-WebRequest -UseBasicParsing -TimeoutSec 60 -Uri ${psQuote(script)} -OutFile $setup -ErrorAction Stop\n    py -3 "$setup" ${flags}\n    if ($LASTEXITCODE -ne 0) { throw 'Setup did not complete.' }\n  } finally {\n    Remove-Item -LiteralPath $setup -Force -ErrorAction SilentlyContinue\n  }\n}`;
    return `(\n  setup_dir="$(mktemp -d)" || exit 1\n  trap 'rm -rf "$setup_dir"' EXIT\n  curl --fail --silent --show-error --proto '=https' --connect-timeout 15 --max-time 60 ${quote(script)} -o "$setup_dir/setup.py" &&\n  python3 "$setup_dir/setup.py" ${flags}\n)`;
  }
