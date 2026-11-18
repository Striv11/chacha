# One-click setup for Chacha's laptop side. Safe to run again: it skips what is already done.
# Started by "Install Chacha.bat". Use -NoStart to set up without starting Chacha.
param([switch]$NoStart)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Have($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }
function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
}
function Get-App($cmd, $id, $name) {
    if (Have $cmd) { Write-Host "$name is already installed."; return }
    if (-not (Have winget)) { throw "Please install $name yourself, then run this again." }
    Write-Host "Installing $name..."
    winget install --id $id -e --accept-source-agreements --accept-package-agreements --silent | Out-Null
    Refresh-Path
    if (-not (Have $cmd)) { throw "$name didn't install. Restart the computer and run this again." }
}

Step "1 of 6: Programs Chacha needs"
Get-App "py" "Python.Python.3.13" "Python"
Get-App "ollama" "Ollama.Ollama" "Ollama (the private AI on this laptop)"
if (-not (Test-Path "C:\Program Files\Tailscale\tailscale.exe")) { Get-App "tailscale" "tailscale.tailscale" "Tailscale (the private link to your phone)" }
else { Write-Host "Tailscale is already installed." }

Step "2 of 6: Chacha's own files"
if (-not (Test-Path ".venv\Scripts\python.exe")) { py -3.13 -m venv .venv }
& .venv\Scripts\python.exe -m pip install -q --disable-pip-version-check -r requirements.txt
& .venv\Scripts\python.exe -m playwright install chromium | Out-Null

Step "3 of 6: Your private settings"
if (-not (Test-Path ".env")) { Copy-Item ".env.example" ".env" }
$envText = Get-Content ".env" -Raw
if ($envText -notmatch "(?m)^BRAIN_TOKEN=\S") {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $token = [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    $envText = $envText -replace "(?m)^BRAIN_TOKEN=.*$", "BRAIN_TOKEN=$token"
    Set-Content ".env" $envText -NoNewline -Encoding utf8
    Write-Host "Made a new secret key for your phone."
} else { Write-Host "Settings already exist." }

Step "4 of 6: The AI models (a few GB, only the first time)"
& .venv\Scripts\python.exe -c "from brain.run import ollama_up, main; import sys; sys.exit(0 if ollama_up() else 1)"
if ($LASTEXITCODE -ne 0) { Start-Process ollama -ArgumentList "serve" -WindowStyle Hidden; Start-Sleep 5 }
foreach ($m in @("qwen3:4b", "qwen3:8b", "qwen2.5vl:3b")) { Write-Host "Getting $m..."; ollama pull $m | Out-Null }

Step "5 of 6: Connect Tailscale"
$ts = "C:\Program Files\Tailscale\tailscale.exe"
$ip = (& $ts ip -4 2>$null)
if (-not $ip) {
    Write-Host "A browser window will open. Sign in to Tailscale, using the same account you'll use on your phone."
    & $ts up
}
else { Write-Host "Tailscale is connected." }

Step "6 of 6: Start Chacha"
& .venv\Scripts\python.exe -m brain.autostart on
if (-not $NoStart) {
    Start-Process ".venv\Scripts\pythonw.exe" -ArgumentList "-m", "brain.run" -WorkingDirectory $root
    Write-Host "Chacha is running."
    Write-Host "`nNow install the Chacha app on your phone, open it, and scan the code that appears next." -ForegroundColor Green
    & .venv\Scripts\python.exe -m brain.connect
}
