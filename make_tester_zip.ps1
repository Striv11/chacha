# For the owner: builds Chacha-tester.zip to hand to a tester. Leaves out your .env, data, .venv and build files.
# Rebuild the APK first if you changed the Android app; this copies the newest one into phone\Chacha.apk.
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root
$apk = "android\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) { New-Item -ItemType Directory -Force phone | Out-Null; Copy-Item $apk "phone\Chacha.apk" -Force }
if (-not (Test-Path "phone\Chacha.apk")) { throw "No phone app found. Build the Android app first." }
$stage = Join-Path $env:TEMP "chacha_pkg\Chacha"
Remove-Item (Split-Path $stage) -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
foreach ($f in @("brain", "phone", "Install Chacha.bat", "Connect phone.bat", "install.ps1", "requirements.txt", ".env.example", "chat.py", "README.md")) {
    Copy-Item $f $stage -Recurse
}
Get-ChildItem $stage -Recurse -Directory -Filter __pycache__ | Remove-Item -Recurse -Force
$out = Join-Path $root "Chacha-tester.zip"
if (Test-Path $out) { $out = Join-Path $root ("Chacha-tester-" + (Get-Date -Format "yyyyMMdd-HHmm") + ".zip") }
Compress-Archive -Path $stage -DestinationPath $out
Write-Host "Made $out. Send it to the tester and tell them to open README.md first."
