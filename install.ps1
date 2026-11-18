# One-click setup for Chacha's laptop side. Safe to run again: it skips what is already done.
# Started by "Install Chacha.bat". Use -NoStart to set up without starting Chacha.
param([switch]$NoStart)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root
New-Item -ItemType Directory -Force "data" | Out-Null
try { Start-Transcript -Path "data\install_log.txt" -Append | Out-Null } catch {}

function Step($n, $text) { Write-Host "`n----------------------------------------------" -ForegroundColor DarkGray; Write-Host " Step $n of 7: $text" -ForegroundColor Cyan }
function Ok($text) { Write-Host "  [OK] $text" -ForegroundColor Green }
function Info($text) { Write-Host "  $text" }
function Warn($text) { Write-Host "  [!] $text" -ForegroundColor Yellow }
function Have($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }
function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
}
function Get-App($cmd, $id, $name) {
    if (Have $cmd) { Ok "$name is already installed."; return }
    if (-not (Have winget)) { throw "This laptop can't install programs by itself (the 'App Installer' from the Microsoft Store is missing). Install it from the Microsoft Store, then double-click 'Install Chacha' again." }
    Info "Installing $name... (this can take a few minutes)"
    winget install --id $id -e --accept-source-agreements --accept-package-agreements --silent | Out-Null
    Refresh-Path
    if (-not (Have $cmd)) { throw "$name didn't finish installing. Restart the laptop and double-click 'Install Chacha' again." }
    Ok "$name installed."
}

Clear-Host
Write-Host ""
Write-Host "   CHACHA  -  your private assistant" -ForegroundColor White
Write-Host "   This sets up Chacha on this laptop. It takes 10-30 minutes," -ForegroundColor Gray
Write-Host "   mostly downloading. You can leave it running." -ForegroundColor Gray
Write-Host "   Keep the laptop plugged in and connected to the internet." -ForegroundColor Gray

try {
    Step 1 "Checking this laptop"
    $ramGB = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
    $freeGB = [math]::Round((Get-PSDrive ($root.Substring(0, 1))).Free / 1GB)
    Info "Memory: $ramGB GB   Free space: $freeGB GB"
    if ($freeGB -lt 12) { throw "Chacha needs about 12 GB of free space and this laptop has $freeGB GB. Free up some space (empty the Recycle Bin, delete big downloads), then run this again." }
    if ($ramGB -lt 8) { Warn "This laptop has less than 8 GB of memory. Chacha will work, but slowly." }
    Ok "This laptop can run Chacha."

    Step 2 "Programs Chacha needs"
    $pyOk = $false
    if (Have "py") { try { py -3.13 --version *> $null; $pyOk = ($LASTEXITCODE -eq 0) } catch {} }
    if ($pyOk) { Ok "Python is already installed." }
    else {
        if (-not (Have winget)) { throw "Please install Python 3.13 from python.org, then run this again." }
        Info "Installing Python..."
        winget install --id Python.Python.3.13 -e --accept-source-agreements --accept-package-agreements --silent | Out-Null
        Refresh-Path; Ok "Python installed."
    }
    Get-App "ollama" "Ollama.Ollama" "Ollama (runs the AI privately on this laptop)"
    if (Test-Path "C:\Program Files\Tailscale\tailscale.exe") { Ok "Tailscale is already installed." }
    else { Get-App "tailscale" "tailscale.tailscale" "Tailscale (the private link between laptop and phone)" }

    Step 3 "Chacha's own files"
    if (-not (Test-Path ".venv\Scripts\python.exe")) { py -3.13 -m venv .venv }
    Info "Getting Chacha's building blocks..."
    & .venv\Scripts\python.exe -m pip install -q --disable-pip-version-check -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw "Couldn't download Chacha's building blocks. Check the internet connection and run this again." }
    & .venv\Scripts\python.exe -m playwright install chromium | Out-Null
    Ok "Done."

    Step 4 "Your private settings"
    if (-not (Test-Path ".env")) { Copy-Item ".env.example" ".env" }
    $envText = Get-Content ".env" -Raw
    if ($envText -notmatch "(?m)^BRAIN_TOKEN=\S") {
        $bytes = New-Object byte[] 32
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $token = [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
        $envText = $envText -replace "(?m)^BRAIN_TOKEN=.*$", "BRAIN_TOKEN=$token"
        Set-Content ".env" $envText -NoNewline -Encoding utf8
        Ok "Made a secret key that only your phone will know."
    } else { Ok "Settings already exist." }

    Step 5 "The private AI (a few GB, only the first time)"
    & .venv\Scripts\python.exe -c "from brain.run import ollama_up; import sys; sys.exit(0 if ollama_up() else 1)"
    if ($LASTEXITCODE -ne 0) { Start-Process ollama -ArgumentList "serve" -WindowStyle Hidden; Start-Sleep 6 }
    $models = @("qwen3:4b", "qwen2.5vl:3b")
    if ($ramGB -ge 12) { $models += "qwen3:8b" } else { Info "Skipping the bigger AI because this laptop has $ramGB GB of memory." }
    $i = 0
    foreach ($m in $models) {
        $i++; Info "Downloading AI part $i of $($models.Count)... (you'll see progress below)"
        ollama pull $m
        if ($LASTEXITCODE -ne 0) { throw "The AI download stopped. Check the internet connection and run this again; it continues where it left off." }
    }
    if ($ramGB -lt 12) {
        $e = (Get-Content ".env" -Raw) -replace "(?m)^SMART_MODEL=.*$", "SMART_MODEL=qwen3:4b"
        if ($e -match "(?m)^CONTEXT_TOKENS=") { $e = $e -replace "(?m)^CONTEXT_TOKENS=.*$", "CONTEXT_TOKENS=8192" } else { $e += "`nCONTEXT_TOKENS=8192`n" }
        Set-Content ".env" $e -NoNewline -Encoding utf8
        Info "Using a smaller reading window so the AI fits in $ramGB GB."
    }
    Ok "The AI is ready. It runs on this laptop only."

    Step 6 "Private link to your phone (Tailscale)"
    $ts = "C:\Program Files\Tailscale\tailscale.exe"
    $ip = (& $ts ip -4 2>$null)
    if (-not $ip) {
        Write-Host ""
        Write-Host "  A browser window will open. Sign in to Tailscale (Google or Microsoft is easiest)." -ForegroundColor Yellow
        Write-Host "  REMEMBER which account you use - you'll use the SAME one on your phone." -ForegroundColor Yellow
        & $ts up
        $ip = (& $ts ip -4 2>$null)
        if (-not $ip) { throw "Tailscale isn't signed in yet. Run this again and finish the sign-in in the browser." }
    }
    Ok "Tailscale is connected."
    if (-not (Get-NetFirewallRule -DisplayName "Chacha (Tailscale only)" -ErrorAction SilentlyContinue)) {
        Info "Windows will ask: 'Do you want to allow this app to make changes?' Click YES."
        Info "(This lets ONLY your own phone, through Tailscale, reach Chacha.)"
        $cmd = "New-NetFirewallRule -DisplayName 'Chacha (Tailscale only)' -Direction Inbound -Protocol TCP -LocalPort 8765 -RemoteAddress 100.64.0.0/10 -Action Allow | Out-Null"
        try { Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList "-NoProfile", "-Command", $cmd } catch { Warn "Skipped. If your phone can't connect later, run 'Install Chacha' again and click Yes." }
    }
    if (Get-NetFirewallRule -DisplayName "Chacha (Tailscale only)" -ErrorAction SilentlyContinue) { Ok "Your phone is allowed in." }

    Step 7 "Start Chacha"
    & .venv\Scripts\python.exe -m brain.autostart on
    $desk = [Environment]::GetFolderPath("Desktop")
    $sc = (New-Object -ComObject WScript.Shell).CreateShortcut("$desk\Chacha - Connect phone.lnk")
    $sc.TargetPath = "$root\Connect phone.bat"; $sc.WorkingDirectory = $root; $sc.Save()
    Ok "Added 'Chacha - Connect phone' to your desktop."
    if (-not $NoStart) {
        Get-CimInstance Win32_Process -Filter "Name='pythonw.exe'" | Where-Object { $_.CommandLine -like "*brain.run*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
        Start-Process ".venv\Scripts\pythonw.exe" -ArgumentList "-m", "brain.run" -WorkingDirectory $root
        Ok "Chacha is running, and will start by itself whenever you sign in."
        Write-Host ""
        Write-Host "  ALL DONE ON THE LAPTOP." -ForegroundColor Green
        Write-Host "  A page opens in your browser now. Follow its 3 steps with your phone in hand." -ForegroundColor Green
        Start-Sleep 4
        & .venv\Scripts\python.exe -m brain.connect
    }
}
catch {
    Write-Host ""
    Write-Host "  Something went wrong:" -ForegroundColor Red
    Write-Host "  $($_.Exception.Message)" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Nothing is broken - you can simply double-click 'Install Chacha' again." -ForegroundColor Yellow
    Write-Host "  If it keeps failing, send the file data\install_log.txt (in the Chacha folder) to the person who gave you Chacha." -ForegroundColor Yellow
}
finally { try { Stop-Transcript | Out-Null } catch {} }
